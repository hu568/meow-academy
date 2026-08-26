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
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * Markdown 渲染配置仓库（JSONC 默认模板 + 用户覆盖，docs/design-dynamic-config.md）。
 *
 * 目录职责：
 * - `config-defaults/markdown-config.jsonc`：默认模板运行时副本（只读，随 APK 升级同步）；
 * - `appconfig/markdown-config.jsonc`：用户文件（默认 = 模板完整副本，用户/AI 直接改，App 更新永不覆盖）；
 * - `appconfig/markdown-config.js`：旧版 JS 配置（首次运行时一次性迁移为 JSONC，之后不再使用）。
 *
 * 加载链：
 * 1. 用户文件缺失 / 解析失败 → 默认模板（config-defaults/）；
 * 2. 默认模板缺失字段 → Kotlin 数据类默认值；
 * 3. 解析失败时保留上次有效配置，首次失败用内置默认，不崩溃。
 *
 * 热更：FileObserver 监听 `appconfig/markdown-config.jsonc`（人工 / DSH AI write 都会触发），
 * 300ms 去抖后重新解析 + 深合并。
 */
class MarkdownConfigRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _config = MutableStateFlow<MarkdownConfigRaw?>(null)
    private var fileObserver: FileObserver? = null
    private var reloadJob: Job? = null

    /** 当前深合并后的原始配置；null = 文件缺失/未加载，渲染时用内置默认值 */
    val config: StateFlow<MarkdownConfigRaw?> = _config.asStateFlow()

    /** 用户文件 appconfig/markdown-config.jsonc */
    val userConfigFile: File
        get() = File(RuntimeExtractor.appConfigDir(context), FILE_NAME)

    /** 默认模板运行时副本 config-defaults/markdown-config.jsonc */
    val defaultsFile: File
        get() = File(RuntimeExtractor.configDefaultsDir(context), FILE_NAME)

    /** 旧版 JS 用户文件 appconfig/markdown-config.js（一次性迁移用） */
    val legacyUserFile: File
        get() = File(RuntimeExtractor.appConfigDir(context), LEGACY_FILE_NAME)

    /** config-defaults/.sync-token（记录上次同步的 versionCode + lastUpdateTime） */
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
            migrateLegacyJsIfNeeded()
            seedDefaultUserFileIfNeeded()

            val defaultsRaw = readJsoncMap(defaultsFile)
            val userText = if (userConfigFile.exists()) runCatching { userConfigFile.readText() }.getOrNull() else null
            if (userText != null) {
                val parsed = runCatching { parseConfigJsonc(userText) }
                val userRaw = parsed.getOrNull()
                if (userRaw == null) {
                    Log.w(TAG, "markdown-config.jsonc 解析失败，保留上次配置: ${parsed.exceptionOrNull()?.message}")
                    if (_config.value != null) return
                    // 首次失败：回退默认模板
                    _config.value = defaultsRaw
                    return
                }
                _config.value = if (defaultsRaw != null) deepMerge(defaultsRaw, userRaw) else userRaw
                Log.d(TAG, "markdown-config.jsonc 已加载，keys=${_config.value?.keys?.size}")
            } else {
                // 用户文件缺失 → 使用默认模板
                _config.value = defaultsRaw
                Log.d(TAG, "markdown-config.jsonc 缺失，使用默认模板")
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
     * 把 config-defaults/ 同步为 APK 内置 assets 的最新副本。
     *
     * 仅当 `.sync-token`（versionCode + lastUpdateTime）变化时才整体复制；
     * 同时把 `config-defaults/README.md` 强制同步到 `appconfig/README.md`。
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

    /**
     * 存量迁移：旧 `appconfig/markdown-config.js` 一次性转为 `appconfig/markdown-config.jsonc`。
     *
     * 设计决策（docs/notes/meow-dynamic-architecture-note.md §11.3-4）：
     * 旧 JS 文件整体当作用户覆盖，不做 diff、不重写用户文件；成功迁移后删除旧文件。
     * 生成的是**带逐项注释**的 JSONC（经 [formatMarkdownConfigJsonc]），
     * 缺失字段由默认模板补齐，用户改过的值全部保留。
     * 转换失败则保留旧文件，由 [seedDefaultUserFileIfNeeded] 用默认模板兜底。
     */
    private fun migrateLegacyJsIfNeeded() {
        if (userConfigFile.exists() && !userConfigFile.isDirectory) return
        if (!legacyUserFile.exists()) return
        val legacyText = runCatching { legacyUserFile.readText() }.getOrNull() ?: return
        val legacyMap = runCatching { evaluateLegacyJs(legacyText) }.getOrNull()
        if (legacyMap == null) {
            Log.w(TAG, "旧 markdown-config.js 求值失败，跳过迁移，改用默认模板 seed")
            return
        }
        val defaultsRaw = readJsoncMap(defaultsFile) ?: emptyMap()
        val merged = deepMerge(defaultsRaw, legacyMap)
        val version = defaultsRaw["version"] as? String ?: DEFAULT_VERSION
        val editable = DEFAULT_EDITABLE_COUNT
        runCatching {
            userConfigFile.parentFile?.mkdirs()
            deleteIfDirectory(userConfigFile)
            userConfigFile.writeText(formatMarkdownConfigJsonc(merged, version, editable))
            legacyUserFile.delete()
            Log.i(TAG, "旧 markdown-config.js 已迁移为带注释的 markdown-config.jsonc")
        }.onFailure {
            Log.w(TAG, "旧 markdown-config.js 迁移写文件失败: ${it.message}")
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
    private fun readJsoncMap(file: File): MarkdownConfigRaw? {
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

    // ── 旧版 JS 求值（仅存量迁移用；迁移完成后不再触发） ────────────────────

    /** Rhino 解释模式求值旧 JS，读取全局 `markdownConfig` 并转成纯 Map */
    private fun evaluateLegacyJs(code: String): Map<String, Any?>? {
        val cx = RhinoContext.enter()
        return try {
            cx.optimizationLevel = -1 // Android 兼容：解释模式，不用编译字节码
            val scope: Scriptable = cx.initStandardObjects()
            cx.evaluateString(scope, code, LEGACY_FILE_NAME, 1, null)
            rhinoToMap(scope.get("markdownConfig", scope))
        } catch (e: Exception) {
            Log.w(TAG, "旧 markdown-config.js 求值失败: ${e.message}")
            null
        } finally {
            RhinoContext.exit()
        }
    }

    private fun rhinoToMap(value: Any?): Map<String, Any?>? = when (value) {
        is NativeObject -> {
            val map = LinkedHashMap<String, Any?>()
            for (id in value.allIds) {
                val key = id.toString()
                map[key] = rhinoToPlain(value.get(key, value))
            }
            map
        }
        is Map<*, *> -> {
            val map = LinkedHashMap<String, Any?>()
            for ((k, v) in value) map[k.toString()] = rhinoToPlain(v)
            map
        }
        else -> null
    }

    private fun rhinoToList(value: Any?): List<Any?>? = when (value) {
        is NativeArray -> {
            val list = ArrayList<Any?>(value.size)
            for (i in 0 until value.size) {
                list += rhinoToPlain(value.get(i, value))
            }
            list
        }
        is List<*> -> value.map { rhinoToPlain(it) }
        else -> null
    }

    private fun rhinoToPlain(value: Any?): Any? = when (value) {
        is NativeObject -> rhinoToMap(value)
        is NativeArray -> rhinoToList(value)
        is Map<*, *> -> rhinoToMap(value)
        is List<*> -> rhinoToList(value)
        is Undefined -> null
        else -> value
    }

    private companion object {
        const val TAG = "MarkdownConfigRepo"
        const val FILE_NAME = "markdown-config.jsonc"
        const val LEGACY_FILE_NAME = "markdown-config.js"
        const val README_NAME = "README.md"
        const val SYNC_TOKEN_FILE = ".sync-token"
        const val ASSET_CONFIG_DEFAULTS = "config-defaults"
        const val DEFAULT_VERSION = "2026-08-24T12:00:00Z"
        const val DEFAULT_EDITABLE_COUNT = 43
        const val RELOAD_DEBOUNCE_MS = 300L
        const val WATCH_MASK = FileObserver.MODIFY or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_TO
    }
}
