package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    /** agent 配置目录名（filesDir 下，skills / 记忆 / 插件，MCP 未来） */
    const val AGENTS_DIR = ".agents"

    /** 创造模式生成的 DSH 插件目录（.agents 下） */
    const val AGENTS_PLUGINS_DIR = ".agents/plugins"

    /** 自定义 skills 目录（.agents 下） */
    const val AGENTS_SKILLS_DIR = ".agents/skills"

    /** 长期记忆目录（.agents 下） */
    const val AGENTS_MEMORY_DIR = ".agents/memory"

    /**
     * 解压 [ASSET_NAME] 到 filesDir/[RUNTIME_DIR]。
     *
     * @param onProgress 0f..1f 的解压进度（按压缩包字节计）
     */
    suspend fun extract(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        // 目录重构（phase4）：解压即把 workspace/appconfig/.agents 建好（三保险之一）
        ensureAppDirs(context)

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
        )
        dirs.forEach { it.mkdirs() }
        Log.d(TAG, "ensureAppDirs ok")
    }

    /** workspace 目录（DSH_CWD） */
    fun workspaceDir(context: Context): File = File(context.filesDir, WORKSPACE_DIR)

    /** workspace 内的上传目录（DSH_UPLOAD_DIR） */
    fun workspaceUploadsDir(context: Context): File = File(workspaceDir(context), UPLOADS_DIR)

    /** appconfig 目录（settings / credentials） */
    fun appConfigDir(context: Context): File = File(context.filesDir, APPCONFIG_DIR)

    /** config-defaults 目录（默认配置模板，随 APK 升级同步） */
    fun configDefaultsDir(context: Context): File = File(context.filesDir, CONFIG_DEFAULTS_DIR)

    /** .agents 根目录 */
    fun agentsDir(context: Context): File = File(context.filesDir, AGENTS_DIR)

    /** .agents/plugins（创造模式插件） */
    fun agentsPluginsDir(context: Context): File = File(context.filesDir, AGENTS_PLUGINS_DIR)

    /** .agents/skills（自定义 skills） */
    fun agentsSkillsDir(context: Context): File = File(context.filesDir, AGENTS_SKILLS_DIR)

    /** .agents/memory（长期记忆） */
    fun agentsMemoryDir(context: Context): File = File(context.filesDir, AGENTS_MEMORY_DIR)
}