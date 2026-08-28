package com.meow.academy.data.settings

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.meow.academy.runtime.RuntimeExtractor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主题颜色动态配置仓库（JSONC 默认模板 + 用户覆盖，docs/design-dynamic-config.md §11）。
 *
 * 与 [MarkdownConfigRepository] 同一套管道（config-defaults 同步 + appconfig 用户文件 + FileObserver 热更）：
 * - `config-defaults/theme-config.jsonc`：默认模板运行时副本（只读，随 APK 升级同步）；
 * - `appconfig/theme-config.jsonc`：用户文件（默认 = 模板完整副本，用户/AI 直接改，App 更新永不覆盖）。
 *
 * 加载链：
 * 1. 用户文件缺失 / 解析失败 → 默认模板（config-defaults/）；
 * 2. 默认模板缺失字段 → Kotlin 内置默认；
 * 3. 解析失败时保留上次有效配置，首次失败用默认模板，不崩溃。
 *
 * 热更：FileObserver 监听 `appconfig/theme-config.jsonc`（人工 / DSH AI write 都会触发），
 * 300ms 去抖后重新解析 + 深合并 → MainActivity 收集后实时换肤。
 */
class ThemeConfigRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _config = MutableStateFlow<ThemeConfigRaw?>(null)
    private var fileObserver: FileObserver? = null
    private var reloadJob: Job? = null

    /** 当前深合并后的原始配置；null = 文件缺失/未加载，渲染时用内置默认值 */
    val config: StateFlow<ThemeConfigRaw?> = _config.asStateFlow()

    /** 用户文件 appconfig/theme-config.jsonc */
    val userConfigFile: File
        get() = File(RuntimeExtractor.appConfigDir(context), FILE_NAME)

    /** 默认模板运行时副本 config-defaults/theme-config.jsonc */
    val defaultsFile: File
        get() = File(RuntimeExtractor.configDefaultsDir(context), FILE_NAME)

    /** config-defaults/.sync-token（与 MarkdownConfigRepository 共用，记录上次同步的 versionCode + lastUpdateTime） */
    private val syncTokenFile: File
        get() = File(RuntimeExtractor.configDefaultsDir(context), SYNC_TOKEN_FILE)

    /** 注册 FileObserver 并触发首次加载（幂等，可重复调用） */
    @Synchronized
    fun start() {
        if (fileObserver != null) return
        scope.launch { load() }
        // 观察 appconfig 目录而不是单文件：文件可能尚未生成，目录级观察能覆盖 CREATE/DELETE
        fileObserver = object : FileObserver(RuntimeExtractor.appConfigDir(context).absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FILE_NAME && event and WATCH_MASK != 0) requestReload()
            }
        }.also { it.startWatching() }
    }

    /** 立即重新加载（IO 线程）；失败时保留上次配置 */
    suspend fun load() {
        mutex.withLock {
            syncConfigDefaultsIfNeeded()
            seedDefaultUserFileIfNeeded()

            val defaultsRaw = readJsoncMap(defaultsFile)
            val userText = if (userConfigFile.exists()) runCatching { userConfigFile.readText() }.getOrNull() else null
            if (userText != null) {
                val parsed = runCatching { parseConfigJsonc(userText) }
                val userRaw = parsed.getOrNull()
                if (userRaw == null) {
                    Log.w(TAG, "theme-config.jsonc 解析失败，保留上次配置: ${parsed.exceptionOrNull()?.message}")
                    if (_config.value != null) return
                    // 首次失败：回退默认模板
                    _config.value = defaultsRaw
                    return
                }
                _config.value = if (defaultsRaw != null) deepMerge(defaultsRaw, userRaw) else userRaw
                Log.d(TAG, "theme-config.jsonc 已加载，keys=${_config.value?.keys?.size}")
            } else {
                // 用户文件缺失 → 使用默认模板
                _config.value = defaultsRaw
                Log.d(TAG, "theme-config.jsonc 缺失，使用默认模板")
            }
        }
    }

    /** 300ms 去抖后重载（FileObserver 回调线程调用） */
    private fun requestReload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            load()
        }
    }

    /**
     * 动态配置模式下，把「当前选中的聊天背景」写回 appconfig/theme-config.jsonc。
     *
     * 只改 `backgrounds.active`，其余字段（seed / overrides / backgrounds.presets 等）**原样保留**；
     * 用带注释的 formatter 重新生成（[formatThemeConfigJsonc]），随后立即重载即时生效。
     *
     * ⚠️ 设计约束（docs/design-dynamic-config.md §1）：`appconfig/` 是用户文件，
     * **禁止**把默认模板深合并后写回——那样会把默认值固化进用户文件，导致以后
     * `config-defaults/` 升级时旧默认值盖住新默认值、用户文件被污染。
     * 这里只基于用户文件自身内容改 `active`，缺字段留给加载链回退默认模板。
     */
    suspend fun updateBackgroundActive(active: String) {
        mutex.withLock {
            syncConfigDefaultsIfNeeded()
            // 用户文件不存在时先 seed 一份默认模板完整副本（仅创建，不覆盖已有）
            seedDefaultUserFileIfNeeded()
            val userText = if (userConfigFile.exists()) runCatching { userConfigFile.readText() }.getOrNull() else null
            val base = if (userText != null) {
                runCatching { parseConfigJsonc(userText) }.getOrNull() ?: emptyMap()
            } else {
                emptyMap()
            }
            // 只改 backgrounds.active：基于用户文件自身（不 deepMerge 默认模板）
            val updated = base.toMutableMap()
            val backgrounds = ((updated["backgrounds"] as? Map<*, *>)?.toStringMapForWrite())
                ?: linkedMapOf<String, Any?>()
            backgrounds["active"] = active
            updated["backgrounds"] = backgrounds
            // 版本保留用户文件里的（若精简文件没写 version 才回退默认模板版本）
            val version = updated["version"] as? String
                ?: readJsoncMap(defaultsFile)?.get("version") as? String
                ?: DEFAULT_VERSION
            runCatching {
                deleteIfDirectory(userConfigFile)
                userConfigFile.parentFile?.mkdirs()
                userConfigFile.writeText(formatThemeConfigJsonc(updated, version, DEFAULT_EDITABLE_COUNT))
                Log.i(TAG, "backgrounds.active 已写回: $active")
            }.onFailure {
                Log.w(TAG, "backgrounds.active 写回失败: ${it.message}")
                return@withLock
            }
            // 立即重载（FileObserver 也会触发，这里主动一次保证即时生效）
            load()
        }
    }

    private fun Map<*, *>.toStringMapForWrite(): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(size)
        for ((k, v) in this) out[k.toString()] = v
        return out
    }

    /**
     * 把 config-defaults/ 同步为 APK 内置 assets 的最新副本。
     *
     * 仅当 `.sync-token`（versionCode + lastUpdateTime）变化时才整体复制；
     * 同时把 `config-defaults/README.md` 强制同步到 `appconfig/README.md`。
     * （与 MarkdownConfigRepository 共用同一 token：整个目录一起拷，幂等无冲突）
     */
    private fun syncConfigDefaultsIfNeeded() {
        val token = buildSyncToken()
        if (syncTokenFile.exists() && syncTokenFile.readText().trim() == token) {
            // token 一致：只做轻量自愈（修复旧版本误把文件建成目录的坏状态），不动模板内容
            repairConfigDefaultsIfBroken()
            syncAppConfigReadme()
            return
        }
        runCatching {
            copyAssetTree(ASSET_CONFIG_DEFAULTS, RuntimeExtractor.configDefaultsDir(context))
            syncAppConfigReadme()
            syncTokenFile.writeText(token)
            Log.i(TAG, "config-defaults 已同步 (token=$token)")
        }.onFailure {
            Log.w(TAG, "config-defaults 同步失败: ${it.message}")
        }
    }

    /** appconfig/README.md 是 config-defaults/README.md 的同步副本，更新时强制覆盖 */
    private fun syncAppConfigReadme() {
        val source = File(RuntimeExtractor.configDefaultsDir(context), README_NAME)
        if (!source.exists() || source.isDirectory) return
        runCatching {
            val appConfigDir = RuntimeExtractor.appConfigDir(context)
            appConfigDir.mkdirs()
            val target = File(appConfigDir, README_NAME)
            deleteIfDirectory(target)
            source.copyTo(target, overwrite = true)
        }.onFailure {
            Log.w(TAG, "appconfig/README.md 同步失败: ${it.message}")
        }
    }

    /** 首次启动把默认模板完整复制到 appconfig/；已有用户文件不覆盖（尊重用户/AI 修改） */
    private fun seedDefaultUserFileIfNeeded() {
        if (userConfigFile.exists() && !userConfigFile.isDirectory) return
        if (!defaultsFile.exists() || defaultsFile.isDirectory) return
        runCatching {
            deleteIfDirectory(userConfigFile)
            userConfigFile.parentFile?.mkdirs()
            defaultsFile.copyTo(userConfigFile, overwrite = false)
            Log.i(TAG, "已生成用户配置: ${userConfigFile.absolutePath}")
        }.onFailure {
            Log.w(TAG, "用户配置 seed 失败: ${it.message}")
        }
    }

    /** 读取并解析 JSONC 文件；文件缺失 / 解析失败返回 null */
    private fun readJsoncMap(file: File): ThemeConfigRaw? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { parseConfigJsonc(text) }.getOrNull()
    }

    private fun buildSyncToken(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.versionCode}:${info.lastUpdateTime}"
    }

    /** 递归复制 assets 目录到 target（保留目录结构） */
    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath)
        if (children != null && children.isNotEmpty()) {
            // 目录
            if (target.exists() && !target.isDirectory) target.deleteRecursively()
            target.mkdirs()
            for (child in children) {
                copyAssetTree("$assetPath/$child", File(target, child))
            }
        } else {
            // 文件（Android 的 AssetManager.list() 对文件路径返回空数组而非 null，必须把空数组也当文件）
            copyAssetFile(assetPath, target)
        }
    }

    /** 把单个 asset 文件复制到 target；target 若是误建的目录则先删除 */
    private fun copyAssetFile(assetPath: String, target: File) {
        if (target.exists() && target.isDirectory) target.deleteRecursively()
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 目标若是目录则删除（自愈误建目录） */
    private fun deleteIfDirectory(file: File) {
        if (file.exists() && file.isDirectory) {
            file.deleteRecursively()
            Log.w(TAG, "自愈：${file.absolutePath} 曾被误建为目录，已删除")
        }
    }

    /**
     * 自愈旧版本 bug：AssetManager.list() 对文件返回空数组导致文件被误建为目录。
     *
     * 在 `.sync-token` 一致（无需整体同步）时也执行，确保存量设备上的坏状态被修复。
     */
    private fun repairConfigDefaultsIfBroken() {
        runCatching {
            val children = context.assets.list(ASSET_CONFIG_DEFAULTS) ?: return
            val defaultsDir = RuntimeExtractor.configDefaultsDir(context)
            for (child in children) {
                val target = File(defaultsDir, child)
                if (target.exists() && target.isDirectory) {
                    target.deleteRecursively()
                    copyAssetFile("$ASSET_CONFIG_DEFAULTS/$child", target)
                    Log.w(TAG, "自愈：config-defaults/$child 已重写为文件")
                }
            }
        }.onFailure {
            Log.w(TAG, "config-defaults 自愈失败: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "ThemeConfigRepo"
        const val FILE_NAME = "theme-config.jsonc"
        const val README_NAME = "README.md"
        const val SYNC_TOKEN_FILE = ".sync-token"
        const val ASSET_CONFIG_DEFAULTS = "config-defaults"
        const val DEFAULT_VERSION = "2026-08-27T14:06:52Z"
        const val DEFAULT_EDITABLE_COUNT = 45
        const val RELOAD_DEBOUNCE_MS = 300L
        const val WATCH_MASK = FileObserver.MODIFY or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_TO
    }
}
