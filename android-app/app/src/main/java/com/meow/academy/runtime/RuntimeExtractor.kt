package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import com.meow.academy.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 把 assets 里的运行时压缩包（tar.gz）解压到应用私有目录。
 *
 * 安全措施：
 *  - 解压到临时目录，完成后原子 rename（避免半解压状态被当成「已就绪」）；
 *  - 校验 tar 条目路径，拒绝 `..` 穿越；
 *  - 解压后对 lib 下的 .bin 与 bin 下的 wrapper 脚本显式加可执行位。
 */
object RuntimeExtractor {

    private const val TAG = "RuntimeExtractor"

    /** assets 中运行时压缩包文件名（gzip 流，用 .bin 后缀避开 AGP 对 .gz 的解压改名） */
    const val ASSET_NAME = "runtime.bin"

    /** 解压目标目录名（filesDir 下） */
    const val RUNTIME_DIR = "meow-runtime"

    /** 版本标记文件（存 assets runtime.bin 的字节数，用于检测是否需要重新解压） */
    const val VERSION_FILE = ".runtime-version"

    /** DSH 默认工作区目录名（filesDir 下，agent 相对路径都落在这里） */
    const val WORKSPACE_DIR = "workspace"

    /** 工作区内的上传目录名 */
    const val UPLOADS_DIR = "uploads"

    /** 设置选项存放目录名（filesDir 下，后续全 JSON） */
    const val APPCONFIG_DIR = "appconfig"

    /** 默认配置模板目录名（filesDir 下，docs/design-dynamic-config.md） */
    const val CONFIG_DEFAULTS_DIR = "config-defaults"

    /** 系统预设播种目录名（filesDir 下，agent-presets 的 system root；assets 同名目录整目录同步） */
    const val DSH_PRESETS_DIR = "dsh-presets"

    /** agent 配置目录名（filesDir 下，skills / 记忆 / 插件，MCP 未来） */
    const val AGENTS_DIR = ".agents"

    /** 创造模式生成的 DSH 插件目录（.agents 下） */
    const val AGENTS_PLUGINS_DIR = ".agents/plugins"

    /** 自定义 skills 目录（.agents 下） */
    const val AGENTS_SKILLS_DIR = ".agents/skills"

    /** 长期记忆目录（.agents 下） */
    const val AGENTS_MEMORY_DIR = ".agents/memory"

    /** 角色库目录（.agents 下，plan-soul 灵魂迁移后角色设定唯一定义处） */
    const val AGENTS_PERSONAS_DIR = ".agents/personas"

    /** 灵魂文件名（角色目录内为人格定义处；.agents/memory 下的同名文件是**存量旧位置**） */
    const val SOUL_FILE = "SOUL.md"

    /** 角色目录名（personas 下的默认角色子目录） */
    const val DEFAULT_PERSONA_DIR = "default"

    /** assets 里角色库的播种源（.agents/personas/ 整目录） */
    private const val PERSONAS_ASSET = "agents/personas"

    /**
     * 解压 [ASSET_NAME] 到 filesDir/[RUNTIME_DIR]。
     *
     * @param onProgress 0f..1f 的解压进度（按压缩包字节计）
     */
    suspend fun extract(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        // 目录重构（phase4）：解压即把 workspace/appconfig/.agents 建好（三保险之一）
        ensureAppDirs(context)
        // 系统预设播种：assets dsh-presets/ → filesDir/dsh-presets/（赶在 DSH 读 roots 之前）
        syncDshPresetsIfNeeded(context)

        val filesDir = context.filesDir
        val targetDir = File(filesDir, RUNTIME_DIR)
        val tmpDir = File(filesDir, "$RUNTIME_DIR.tmp")

        // 清理残留临时目录
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val totalBytes = assetByteSize(context)
        Log.i(TAG, "extract start, totalBytes=$totalBytes")

        var readBytes = 0L
        context.assets.open(ASSET_NAME).use { asset ->
            GZIPInputStream(BufferedInputStream(asset)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // 路径穿越防护
                        val normalized = name.replace('\\', '/').removePrefix("./")
                        // 拒绝 `..` 穿越（`a/../b` → `b` 也算穿越）与绝对路径条目
                        // （`File(parent, "/abs")` 会忽略 parent 直接落到绝对路径，必须拒绝）
                        if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
                            Log.w(TAG, "skip unsafe entry: $name")
                            entry = tar.nextEntry
                            continue
                        }
                        // 剥离顶层目录前缀（真机打包用 `tar -cf runtime.tar meow-runtime`，条目带 meow-runtime/ 前缀）
                        val stripped = normalized
                            .removePrefix("meow-runtime/")
                            .removePrefix("meow-runtime")
                        if (stripped.isEmpty()) {
                            entry = tar.nextEntry
                            continue
                        }
                        val outFile = File(tmpDir, stripped)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else if (entry is org.apache.commons.compress.archivers.tar.TarArchiveEntry &&
                            entry.isSymbolicLink
                        ) {
                            // symlink 条目：尝试创建真实 symlink（API 26+）；失败则跳过并记录
                            outFile.parentFile?.mkdirs()
                            val target = entry.linkName
                            runCatching {
                                java.nio.file.Files.createSymbolicLink(
                                    outFile.toPath(),
                                    java.nio.file.Paths.get(target),
                                )
                            }.onFailure {
                                Log.w(TAG, "symlink 创建失败（跳过）: $name -> $target : ${it.message}")
                            }
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var n: Int
                                while (tar.read(buffer).also { n = it } != -1) {
                                    out.write(buffer, 0, n)
                                    readBytes += n
                                    if (totalBytes > 0) {
                                        onProgress((readBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }

        // lib/*.bin（node/bash 真 ELF）加可执行位：经 linker64 加载，执行位备用
        for (bin in listOf("lib/node.bin", "lib/bash.bin")) {
            val f = File(tmpDir, bin)
            if (f.exists()) f.setExecutable(true, false)
        }
        // bin/node、bin/bash 是 wrapper 脚本（#!/system/bin/sh），终端/bash 工具按 PATH 命中
        // 时 kernel 要 exec 它，必须带可执行位（否则 EACCES）
        for (wrapper in listOf("bin/node", "bin/bash")) {
            val f = File(tmpDir, wrapper)
            if (f.exists()) f.setExecutable(true, false)
        }

        // 原子替换
        if (targetDir.exists()) targetDir.deleteRecursively()
        if (!tmpDir.renameTo(targetDir)) {
            // rename 失败（罕见）：直接移动内容
            tmpDir.copyRecursively(targetDir)
            tmpDir.deleteRecursively()
        }
        onProgress(1f)
        // 写版本标记（assets 字节数），供 isInstalled 检测 runtime.bin 变化
        runCatching { File(targetDir, VERSION_FILE).writeText(totalBytes.toString()) }
        Log.i(TAG, "extract done -> ${targetDir.absolutePath}")
        targetDir
    }

    /** 运行时是否已解压就绪，且与当前 assets 的 runtime.bin 版本一致 */
    fun isInstalled(context: Context): Boolean {
        val dir = File(context.filesDir, RUNTIME_DIR)
        if (!File(dir, "lib/node.bin").exists()
            || !File(dir, "node_modules").exists()
            || !File(dir, "dsh/cordis.yml").exists()
        ) return false
        // 版本标记：解压时写入 assets 的字节数；runtime.bin 变了就触发重新解压
        val versionFile = File(dir, VERSION_FILE)
        if (!versionFile.exists()) return false
        val stored = versionFile.readText().trim().toLongOrNull() ?: return false
        return stored == assetsSize(context)
    }

    /** assets 里 runtime.bin 的字节数（openFd().length 对 gzip 流返回 -1，用 available()） */
    private fun assetByteSize(context: Context): Long =
        runCatching { context.assets.open(ASSET_NAME).use { it.available().toLong() } }.getOrDefault(-1L)

    private fun assetsSize(context: Context): Long = assetByteSize(context)

    /** 运行时目录 */
    fun runtimeDir(context: Context): File = File(context.filesDir, RUNTIME_DIR)

    /**
     * 幂等创建 App 业务目录（workspace / appconfig / .agents 及其子目录）。
     *
     * 三保险调用点：MeowAcademyApp.onCreate、extract()、DshProcessLauncher.launch()。
     * mkdirs() 幂等，重复调用安全喵。
     */
    fun ensureAppDirs(context: Context) {
        val filesDir = context.filesDir
        val dirs = listOf(
            File(filesDir, WORKSPACE_DIR),
            File(filesDir, "$WORKSPACE_DIR/$UPLOADS_DIR"),
            File(filesDir, APPCONFIG_DIR),
            File(filesDir, CONFIG_DEFAULTS_DIR),
            File(filesDir, AGENTS_DIR),
            File(filesDir, AGENTS_PLUGINS_DIR),
            File(filesDir, AGENTS_SKILLS_DIR),
            File(filesDir, AGENTS_MEMORY_DIR),
            File(filesDir, AGENTS_PERSONAS_DIR),
        )
        dirs.forEach { it.mkdirs() }
        // 角色库播种：README.md + skills/ + 内置 default 角色缺则播种、永不覆盖（用户/AI 可改）
        seedPersonasIfNeeded(context)
        // 存量迁移：老版本（0.2.7 及之前）写在 .agents/memory/SOUL.md 的人格 → 迁进 default 角色
        // 顺序敏感——必须在 seedPersonasIfNeeded 之后（default/ 空白模板先就位，§5.1）
        migrateLegacySoulIfNeeded(context)
        Log.d(TAG, "ensureAppDirs ok")
    }

    /**
     * 存量人格迁移（plan-memory-execution §5.1）。
     *
     * 老版本把人物设定写在 `.agents/memory/SOUL.md`（经基座 persona 的 {{soul}} 实时注入）；
     * 记忆系统落地后人格归 `.agents/personas/default/SOUL.md`。本函数只做路径拼装与日志，
     * 实际搬迁逻辑在纯文件版 [migrateLegacySoul]（可 JVM 单测）。
     *
     * 调用点随 [ensureAppDirs] 三保险，且必须在 seedPersonasIfNeeded 之后
     * （default/ 空白模板先就位，§5.1）；均早于 DSH 进程首次组装提示词。
     */
    private fun migrateLegacySoulIfNeeded(context: Context) {
        runCatching {
            val legacy = File(agentsMemoryDir(context), SOUL_FILE)
            val target = File(File(agentsPersonasDir(context), DEFAULT_PERSONA_DIR), SOUL_FILE)
            val backup = File(legacy.parentFile, "$SOUL_FILE.bak")
            when (migrateLegacySoul(legacy, target, backup)) {
                SoulMigration.NO_LEGACY -> Unit
                SoulMigration.MIGRATED -> Log.i(TAG, "存量人格已迁移 ${legacy.absolutePath} -> ${target.absolutePath}")
                SoulMigration.TARGET_OCCUPIED -> Log.i(TAG, "default/SOUL.md 已填写，保留目标、仅备份旧文件")
                SoulMigration.BACKED_UP_ONLY -> Log.i(TAG, "存量 SOUL.md 是空白模板，仅备份不迁移")
            }
        }.onFailure {
            Log.w(TAG, "存量 SOUL.md 迁移失败: ${it.message}")
        }
    }

    /**
     * 是否有实质文字（与 DSH 侧 meow-jsonrpc 的 hasSubstantiveContent 同规则）：
     * 去掉 HTML 注释块与 markdown 注释行后仍有内容才算。空白模板 = 只有注释 → false。
     */
    internal fun hasSubstantiveContent(text: String): Boolean =
        text.replace(Regex("<!--[\\s\\S]*?-->"), "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(" ")
            .isNotBlank()

    /**
     * 角色库播种：assets `agents/personas/` → `filesDir/.agents/personas/`。
     *
     * 按子项分别判断「缺则播种、永不覆盖」：
     * - README.md / skills/：目标缺失才复制（永不覆盖——用户/AI 可改坏自担）；
     * - 内置角色 `default/`：单独判断 `default/persona.yml` 是否存在，
     *   不存在才复制（确保升级后新增的默认角色能补种，不被 README 存在的整树跳过）。
     * assets 未带该目录（老 APK）则静默跳过。
     */
    private fun seedPersonasIfNeeded(context: Context) {
        runCatching {
            val assetsChildren = context.assets.list(PERSONAS_ASSET) ?: return
            if (assetsChildren.isEmpty()) return
            val targetDir = agentsPersonasDir(context)
            targetDir.mkdirs()

            // 1) README.md：缺则播种
            val readme = File(targetDir, "README.md")
            if (!readme.exists()) copyAssetFile(context, "$PERSONAS_ASSET/README.md", readme)

            // 2) skills/：目录缺失才整树复制
            val skillsTarget = File(targetDir, "skills")
            if (!skillsTarget.exists()) {
                copyAssetTree(context, "$PERSONAS_ASSET/skills", skillsTarget)
            }

            // 3) 内置默认角色 default/：persona.yml 缺失才复制（补种升级场景）
            val defaultTarget = File(targetDir, "default")
            if (!File(defaultTarget, "persona.yml").exists()) {
                copyAssetTree(context, "$PERSONAS_ASSET/default", defaultTarget)
            }

            Log.i(TAG, "personas 角色库播种检查完成 -> ${targetDir.absolutePath}")
        }.onFailure {
            Log.w(TAG, "personas 角色库播种失败: ${it.message}")
        }
    }

    /**
     * workspace 目录（DSH_CWD）：按设置返回（DataStore workspacePath，默认 filesDir/workspace 兼容存量）。
     * 切换工作区 = 只写 DataStore、不重启 DSH；DSH_CWD / DSH_UPLOAD_DIR 在进程启动时取一次值（§4.6）。
     */
    fun workspaceDir(context: Context): File = File(currentWorkspacePath(context))

    /** 当前工作区绝对路径：同步读设置 workspacePath（DataStore 进程内有内存缓存，读开销极小） */
    fun currentWorkspacePath(context: Context): String =
        runBlocking { SettingsRepository(context.applicationContext).workspacePath.first() }

    /** workspace 内的上传目录（DSH_UPLOAD_DIR），跟随当前工作区 */
    fun workspaceUploadsDir(context: Context): File = File(workspaceDir(context), UPLOADS_DIR)

    /** appconfig 目录（settings / credentials） */
    fun appConfigDir(context: Context): File = File(context.filesDir, APPCONFIG_DIR)

    /** config-defaults 目录（默认配置模板，随 APK 升级同步） */
    fun configDefaultsDir(context: Context): File = File(context.filesDir, CONFIG_DEFAULTS_DIR)

    /** dsh-presets 目录（agent-presets 的 system root，cordis.yml roots 指向） */
    fun dshPresetsDir(context: Context): File = File(context.filesDir, DSH_PRESETS_DIR)

    /**
     * 系统预设播种：assets `dsh-presets/` 整目录同步到 `filesDir/dsh-presets/`
     * （照 config-defaults 的 sync-token 模式：versionCode + lastUpdateTime 变化才整体复制）。
     *
     * - 通用目录同步，不写死预设名（assets 下有什么预设目录就播什么）；
     * - assets 无 `dsh-presets/`（老 APK 或资产未带）→ 不播种也不写 token，资产到位后下次触发再播；
     * - 触发点：[extract]（解压时）与 [DshProcessLauncher.launch]（进程拉起前），都要赶在 DSH 读 roots 之前。
     */
    fun syncDshPresetsIfNeeded(context: Context) {
        runCatching {
            val children = context.assets.list(DSH_PRESETS_ASSET)
            if (children == null || children.isEmpty()) return
            val token = buildSyncToken(context)
            val targetDir = dshPresetsDir(context)
            val tokenFile = File(targetDir, SYNC_TOKEN_FILE)
            if (tokenFile.exists() && tokenFile.readText().trim() == token) return
            copyAssetTree(context, DSH_PRESETS_ASSET, targetDir)
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
            Log.i(TAG, "dsh-presets 已同步 (token=$token)")
        }.onFailure {
            Log.w(TAG, "dsh-presets 同步失败: ${it.message}")
        }
    }

    /** 同步 token（versionCode + lastUpdateTime，与 config-defaults 同款） */
    private fun buildSyncToken(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.versionCode}:${info.lastUpdateTime}"
    }

    /** 递归复制 assets 目录到 target（保留目录结构） */
    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath)
        if (children != null && children.isNotEmpty()) {
            // 目录
            if (target.exists() && !target.isDirectory) target.deleteRecursively()
            target.mkdirs()
            for (child in children) {
                copyAssetTree(context, "$assetPath/$child", File(target, child))
            }
        } else {
            // 文件（AssetManager.list() 对文件路径返回空数组而非 null，必须把空数组也当文件）
            copyAssetFile(context, assetPath, target)
        }
    }

    /** 把单个 asset 文件复制到 target；target 若是误建的目录则先删除 */
    private fun copyAssetFile(context: Context, assetPath: String, target: File) {
        if (target.exists() && target.isDirectory) target.deleteRecursively()
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** .agents 根目录 */
    fun agentsDir(context: Context): File = File(context.filesDir, AGENTS_DIR)

    /** .agents/plugins（创造模式插件） */
    fun agentsPluginsDir(context: Context): File = File(context.filesDir, AGENTS_PLUGINS_DIR)

    /** .agents/skills（自定义 skills） */
    fun agentsSkillsDir(context: Context): File = File(context.filesDir, AGENTS_SKILLS_DIR)

    /** .agents/memory（长期记忆） */
    fun agentsMemoryDir(context: Context): File = File(context.filesDir, AGENTS_MEMORY_DIR)

    /** .agents/personas（角色库） */
    fun agentsPersonasDir(context: Context): File = File(context.filesDir, AGENTS_PERSONAS_DIR)

    private const val DSH_PRESETS_ASSET = "dsh-presets"

    /** dsh-presets 的同步 token 文件（放在播种目录内，与 config-defaults 同款约定） */
    private const val SYNC_TOKEN_FILE = ".sync-token"

    /** 存量人格迁移的结果（日志分支与 JVM 单测断言共用） */
    internal enum class SoulMigration {
        /** 旧位置无 SOUL.md（新装用户 / 已迁移过）→ 未做任何事 */
        NO_LEGACY,

        /** 旧文件有实质内容且 default/SOUL.md 仍是空白模板 → 已迁入并备份 */
        MIGRATED,

        /** default/SOUL.md 已被用户/AI 填写 → 保留目标内容，只备份旧文件（不冲掉） */
        TARGET_OCCUPIED,

        /** 旧文件本身是空白模板 → 不迁移，只备份 */
        BACKED_UP_ONLY,
    }

    /**
     * 纯文件版存量人格迁移（只依赖 [File]，不需要 Context，故可 JVM 单测）。
     *
     * 规则见 [migrateLegacySoulIfNeeded] 的文档。备份一律落在与旧文件同目录的
     * `SOUL.md.bak`；改名失败时退化为「复制 + 删除」，两条路径都保证旧位置不再留 SOUL.md
     * （幂等：二次调用走 NO_LEGACY）。
     */
    internal fun migrateLegacySoul(legacy: File, target: File, backup: File): SoulMigration {
        if (!legacy.exists()) return SoulMigration.NO_LEGACY
        val legacyText = runCatching { legacy.readText() }.getOrNull() ?: ""
        val targetText = runCatching { target.readText() }.getOrNull() ?: ""
        val outcome = when {
            hasSubstantiveContent(legacyText) && !hasSubstantiveContent(targetText) -> {
                target.parentFile?.mkdirs()
                target.writeText(legacyText)
                SoulMigration.MIGRATED
            }
            hasSubstantiveContent(targetText) -> SoulMigration.TARGET_OCCUPIED
            else -> SoulMigration.BACKED_UP_ONLY
        }
        // renameTo 目标是已存在的普通文件时覆盖（重复迁移也幂等）；失败退化为复制+删除
        if (!legacy.renameTo(backup)) {
            runCatching {
                legacy.copyTo(backup, overwrite = true)
                legacy.delete()
            }
        }
        return outcome
    }
}