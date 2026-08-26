package com.meow.academy.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.meow.academy.runtime.RuntimeExtractor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 文件去重导入结果：duplicated=true 表示命中 uploads 里已有相同内容文件，直接复用不复制 */
data class ImportResult(
    val file: File,
    val duplicated: Boolean,
)

/**
 * 文件管理数据层（M4）：纯 java.io.File 操作，不经过任何 DSH 进程。
 *
 * 所有 suspend 方法都在 [Dispatchers.IO] 上执行；写路径统一「不覆盖」，
 * 配合 [isWithinRoot] 防止路径逃逸到 App 私有目录之外（喵~）。
 */
class FileRepository(private val context: Context) {

    /** 列出目录下子项：文件夹优先 + 名称不区分大小写；目录不存在/不可读返回空列表 */
    suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { file ->
                FileEntry(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    size = file.length(),
                    lastModified = file.lastModified(),
                )
            }
            ?: emptyList()
    }

    /** 读取 UTF-8 文本；文件不存在（或读取失败）返回空串 */
    suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext ""
        runCatching { file.readText() }.getOrDefault("")
    }

    /** 写 UTF-8 文本（父目录须已存在，否则异常向上抛给调用方） */
    suspend fun writeText(path: String, content: String) {
        withContext(Dispatchers.IO) { File(path).writeText(content) }
    }

    /** 新建文件；名字非法或文件已存在返回 false（不覆盖） */
    suspend fun createFile(parentPath: String, name: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidName(name)) return@withContext false
        runCatching { File(parentPath, name).createNewFile() }.getOrDefault(false)
    }

    /** 新建单层目录；名字非法、目录已存在或父目录缺失返回 false */
    suspend fun createDirectory(parentPath: String, name: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidName(name)) return@withContext false
        runCatching { File(parentPath, name).mkdir() }.getOrDefault(false)
    }

    /** 同目录重命名；名字非法、源不存在或目标已存在返回 false */
    suspend fun rename(path: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidName(newName)) return@withContext false
        val file = File(path)
        val parent = file.parentFile ?: return@withContext false
        if (!file.exists()) return@withContext false
        val dest = File(parent, newName)
        if (dest.exists()) return@withContext false
        runCatching { file.renameTo(dest) }.getOrDefault(false)
    }

    /** 删除文件，或递归删除目录 */
    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext false
        runCatching { file.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 复制文件/目录（递归）到目标目录。
     * 目标目录中已有同名项（文件或目录）时**不覆盖**，整体返回 false（先预检后动手，避免半途而废）。
     */
    suspend fun copy(paths: List<String>, targetDir: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(targetDir)
        if (!target.isDirectory) return@withContext false
        // 预检：源必须存在、目标不得有同名项、同一批内名字不得重复
        val seen = mutableSetOf<String>()
        for (p in paths) {
            val src = File(p)
            val name = src.name
            if (!src.exists() || !seen.add(name) || File(target, name).exists()) return@withContext false
        }
        for (p in paths) {
            val src = File(p)
            if (!copyRecursive(src, File(target, src.name))) return@withContext false
        }
        true
    }

    /**
     * 移动文件/目录到目标目录：同盘优先 renameTo；失败或跨盘（不同文件系统）时复制+删除源。
     * 目标已有同名项时不覆盖，整体返回 false。
     */
    suspend fun move(paths: List<String>, targetDir: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(targetDir)
        if (!target.isDirectory) return@withContext false
        val seen = mutableSetOf<String>()
        for (p in paths) {
            val src = File(p)
            val name = src.name
            if (!src.exists() || !seen.add(name) || File(target, name).exists()) return@withContext false
        }
        for (p in paths) {
            val src = File(p)
            val dest = File(target, src.name)
            if (src.renameTo(dest)) continue
            // renameTo 失败（含跨盘）：改走复制 + 删除源
            if (!copyRecursive(src, dest)) return@withContext false
            if (!src.deleteRecursively()) return@withContext false
        }
        true
    }

    /**
     * SAF 导入：逐个 Uri 读取并复制到目标目录，返回与 [uris] 顺序对应的成功与否。
     * 文件名取 OpenableColumns.DISPLAY_NAME，取不到用 lastPathSegment 兜底；同名不覆盖。
     */
    suspend fun importFromUris(uris: List<Uri>, targetDir: String): List<Boolean> = withContext(Dispatchers.IO) {
        val dir = File(targetDir)
        if (!dir.isDirectory) return@withContext List(uris.size) { false }
        uris.map { uri ->
            runCatching {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: return@runCatching false
                if (!isValidName(name)) return@runCatching false
                val dest = File(dir, name)
                if (dest.exists()) return@runCatching false // 不覆盖
                val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
                input.use { source ->
                    dest.outputStream().use { output -> source.copyTo(output) }
                }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 聊天上传专用：先算内容 SHA-256，在 [targetDir] 里找相同内容的文件。
     * - 内容相同（哪怕名字不同）→ 复用已有文件，不重复存储；
     * - 内容不同但名字相同 → 自动追加 " (n)" 序号，两个文件都在 uploads 里保留。
     *
     * @return 实际引用的文件 + 是否命中已有文件；失败返回 null。
     * 全程流式读/写，不把文件内容整块读进内存（二进制、大文件安全喵）。
     */
    suspend fun importDeduplicated(uri: Uri, targetDir: String): ImportResult? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(targetDir)
            if (!dir.isDirectory) return@withContext null
            val hash = sha256Uri(uri) ?: return@withContext null
            val existing = dir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile }
                ?.firstOrNull { sha256File(it) == hash }
            if (existing != null) return@withContext ImportResult(existing, duplicated = true)

            val baseName = queryDisplayName(uri) ?: uri.lastPathSegment ?: return@withContext null
            if (!isValidName(baseName)) return@withContext null
            val dest = uniqueDest(dir, baseName)
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source ->
                dest.outputStream().use { output -> source.copyTo(output) }
            }
            ImportResult(dest, duplicated = false)
        }.getOrNull()
    }

    /**
     * 递归搜索 [root] 下的文件/目录树：跳过以 '.' 开头的隐藏目录（不收录、不深入），
     * 名字 contains([query], ignoreCase = true) 命中，最多返回 200 条；
     * 遍历循环内调用 [ensureActive] 支持协程取消。
     */
    suspend fun search(root: String, query: String): List<FileSearchResult> = withContext(Dispatchers.IO) {
        val base = File(root)
        if (!base.isDirectory) return@withContext emptyList()
        val results = mutableListOf<FileSearchResult>()

        fun walk(dir: File, relativeParent: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                ensureActive() // 支持协程取消（喵~）
                if (results.size >= MAX_SEARCH_RESULTS) return
                val name = child.name
                if (child.isDirectory && name.startsWith('.')) continue // 隐藏目录整棵跳过
                val relativePath = if (relativeParent.isEmpty()) name else "$relativeParent/$name"
                if (name.contains(query, ignoreCase = true)) {
                    results += FileSearchResult(
                        path = child.absolutePath,
                        name = name,
                        isDirectory = child.isDirectory,
                        relativePath = relativePath,
                    )
                }
                if (child.isDirectory) walk(child, relativePath)
            }
        }

        walk(base, "")
        results
    }

    // ── 非 suspend 工具方法 ──

    /** 解析根目录（与 [FileRoot.resolve] 同语义）；INTERNAL = 工作区（filesDir/workspace），EXTERNAL 不可用时返回 null */
    fun resolveRoot(root: FileRoot): File? = when (root) {
        FileRoot.INTERNAL -> RuntimeExtractor.workspaceDir(context)
        FileRoot.EXTERNAL -> context.getExternalFilesDir(null)
    }

    /**
     * 导航用路径归一化：校验目录存在，并把 canonical 形式映射回与 [resolveRoot] 一致的
     * absolutePath 形式。部分设备上 /data/user/0 与 /data/data 互为符号链接，
     * canonicalPath 会改变路径形式；若把 canonical 直接存进 currentPath，
     * 面包屑/目录栈的前缀拼接会全部失配，拆出一堆指向不存在路径的假段（喵~）。
     * 目录不存在或不是文件夹返回 null。
     */
    fun normalizeNavigationPath(path: String): String? {
        val file = File(path)
        if (!file.isDirectory) return null
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
        for (root in roots) {
            val rootAbsolute = root.absolutePath
            val rootCanonical = runCatching { root.canonicalPath }.getOrDefault(rootAbsolute)
            if (canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)) {
                val relative = canonical.removePrefix(rootCanonical).trim('/')
                return if (relative.isEmpty()) rootAbsolute else "$rootAbsolute/$relative"
            }
        }
        return canonical
    }

    /**
     * 手动路径跳转的根判定。phase4（A-1）：workspace 与 filesDir 都属于 INTERNAL，
     * 用户手动输入 filesDir 下的系统目录路径仍允许进入（如 .dsh-sessions）。
     */
    fun resolveRootForPath(path: String): FileRoot? {
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        val filesCanonical = runCatching { context.filesDir.canonicalPath }.getOrNull()
        if (filesCanonical != null && (canonical == filesCanonical || canonical.startsWith(filesCanonical + File.separator))) {
            return FileRoot.INTERNAL
        }
        val externalCanonical = context.getExternalFilesDir(null)
            ?.let { runCatching { it.canonicalPath }.getOrNull() }
        if (externalCanonical != null && (canonical == externalCanonical || canonical.startsWith(externalCanonical + File.separator))) {
            return FileRoot.EXTERNAL
        }
        return null
    }

    /**
     * 目录栈基准路径。INTERNAL 时优先用 workspace 作栈底；
     * path 落在 workspace 之外的 filesDir 下（A-1 手动输入系统目录）时，以 filesDir 为栈底。
     */
    fun stackBaseFor(root: FileRoot, path: String): String? {
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return null
        return when (root) {
            FileRoot.INTERNAL -> {
                val workspaceCanonical = resolveRoot(FileRoot.INTERNAL)
                    ?.let { runCatching { it.canonicalPath }.getOrNull() }
                if (workspaceCanonical != null && (canonical == workspaceCanonical || canonical.startsWith(workspaceCanonical + File.separator))) {
                    resolveRoot(FileRoot.INTERNAL)?.absolutePath
                } else {
                    val filesCanonical = runCatching { context.filesDir.canonicalPath }.getOrNull()
                    if (filesCanonical != null && (canonical == filesCanonical || canonical.startsWith(filesCanonical + File.separator))) {
                        context.filesDir.absolutePath
                    } else {
                        null
                    }
                }
            }
            FileRoot.EXTERNAL -> resolveRoot(FileRoot.EXTERNAL)?.absolutePath
        }
    }

    /** 路径必须在 filesDir 或 getExternalFilesDir(null) 任一根内（canonicalPath 前缀判断，防路径逃逸） */
    fun isWithinRoot(path: String): Boolean {
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
        return roots.any { root ->
            val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
        }
    }

    /** 名字合法性：非空、不含 '/'、不含 NUL 字符 */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && '/' !in name && '\u0000' !in name

    /**
     * 文本文件判定：扩展名白名单命中即真；
     * 否则小文件（< 64KB）读前 8KB 嗅探，无 NUL 字节视为文本。
     */
    fun isTextFile(file: File): Boolean {
        if (file.extension.lowercase() in TEXT_EXTENSIONS) return true
        if (file.length() >= TEXT_SNIFF_SMALL_FILE_LIMIT) return false
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(TEXT_SNIFF_BYTES)
                var total = 0
                while (total < TEXT_SNIFF_BYTES) {
                    val read = input.read(buffer, total, TEXT_SNIFF_BYTES - total)
                    if (read <= 0) break
                    total += read
                }
                (0 until total).none { buffer[it] == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    /** Markdown 判断（.md / .markdown，不区分大小写） */
    fun isMarkdown(name: String): Boolean =
        name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)

    /** HTML 判断（.html / .htm / .xhtml，不区分大小写；xml 不算，仍走文本编辑） */
    fun isHtmlFile(name: String): Boolean =
        name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true) ||
            name.endsWith(".xhtml", ignoreCase = true)

    /** 图片判断：扩展名白名单命中即真（用于点击打开浮窗预览，喵~） */
    fun isImageFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /** 递归复制单个文件或目录（不覆盖已存在的目标） */
    private fun copyRecursive(src: File, dest: File): Boolean =
        runCatching { src.copyRecursively(dest, overwrite = false) }.isSuccess

    /** SAF Uri 内容 SHA-256（流式计算，不会把整个文件读进内存） */
    private fun sha256Uri(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { source ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrNull()

    /** 本地文件内容 SHA-256（流式计算） */
    private fun sha256File(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrNull()

    /** 目标目录内不冲突的文件；冲突时按 "原名 (n).ext" 追加序号（喵~） */
    private fun uniqueDest(dir: File, baseName: String): File {
        var dest = File(dir, baseName)
        if (!dest.exists()) return dest
        val dot = baseName.lastIndexOf('.')
        var index = 1
        while (true) {
            val candidate = if (dot > 0) {
                "${baseName.substring(0, dot)} ($index)${baseName.substring(dot)}"
            } else {
                "$baseName ($index)"
            }
            dest = File(dir, candidate)
            if (!dest.exists()) return dest
            index++
        }
    }

    /** 从 SAF Uri 查询 DISPLAY_NAME；查询失败返回 null */
    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) name = cursor.getString(index)
            }
        }
        return name
    }

    /**
     * 用 java.io.File 构造 [FileEntry]：用于从保存的 path 字符串还原条目（切换 tab 回来 / 跨配置变化恢复时）。
     * 文件不存在时返回 null，调用方应据此退出编辑器/预览（喵~）。
     */
    fun toFileEntry(file: File): FileEntry? {
        if (!file.exists()) return null
        return FileEntry(
            name = file.name,
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
        )
    }

    companion object {
        /** 文本预览/编辑读入内存的上限（1MB） */
        const val TEXT_PREVIEW_LIMIT = 1L * 1024 * 1024

        /** 文本文件扩展名白名单（统一小写比较；与文件列表图标分类对齐） */
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "yaml", "yml", "log", "kt",
            "ts", "js", "xml", "html", "css", "env", "properties", "csv", "toml",
            // 代码 / 网页 / 数据类扩展名：图标显示为文本类，点击也应能编辑
            "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h", "hpp", "swift", "sql",
            "sh", "bat", "ps1", "rb", "php", "scala", "dart", "lua", "vim",
            "jsonl", "jsonc", "htm", "xhtml", "ini", "conf",
        )

        /** 文本嗅探读取字节数（8KB） */
        private const val TEXT_SNIFF_BYTES = 8 * 1024

        /** SHA-256 分块读取缓冲区（64KB，平衡大文件哈希速度与内存占用） */
        private const val HASH_BUFFER_SIZE = 64 * 1024

        /** 超过该大小的无扩展名/未知扩展名文件不再嗅探（视为二进制） */
        private const val TEXT_SNIFF_SMALL_FILE_LIMIT = 64L * 1024

        /** 搜索最多返回条数 */
        private const val MAX_SEARCH_RESULTS = 200
    }
}
