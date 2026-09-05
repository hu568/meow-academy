package com.meow.academy.data.files

import android.util.Log
import java.io.File

/**
 * 文件类型扩展名常量与纯判断工具（喵~）。
 *
 * 跨 data/UI 共用的文件类型判断集中于此，避免两处漂移。
 * - 图片/文本扩展名同时被文件列表图标分类（[com.meow.academy.ui.files.fileKindOf]）
 *   和打开判定（[isImageFile]）引用，集中放一起避免两处漂移。
 * - 5 个纯判断函数（[isTextFile]/[isMarkdown]/[isHtmlFile]/[isImageFile]/[isValidName]）
 *   与文本相关常量从 FileRepository 迁入（2026-08-31 职责拆分），保持同包依赖方向 ui → data。
 * - [FileRepository.TEXT_PREVIEW_LIMIT] 保留在 FileRepository.companion（UI 直接引用，
 *   迁移会破坏零改动红线）。
 */
val IMAGE_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp",
    "svg", "ico", "heic", "avif",
)

/** 文本文件扩展名白名单（统一小写比较；与文件列表图标分类对齐） */
val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "log", "kt",
    "ts", "js", "xml", "html", "css", "env", "properties", "csv", "toml",
    // 代码 / 网页 / 数据类扩展名：图标显示为文本类，点击也应能编辑
    "tsx", "jsx", "py", "go", "rs", "c", "cpp", "h", "hpp", "swift", "sql",
    "sh", "bat", "ps1", "rb", "php", "scala", "dart", "lua", "vim",
    "jsonl", "jsonc", "htm", "xhtml", "ini", "conf",
)

/** 文本嗅探读取字节数（8KB） */
const val TEXT_SNIFF_BYTES = 8 * 1024

/** 超过该大小的无扩展名/未知扩展名文件不再嗅探（视为二进制） */
const val TEXT_SNIFF_SMALL_FILE_LIMIT = 64L * 1024

/** 日志 TAG（纯判断工具的日志用，喵~） */
private const val TAG = "FileTypes"

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
    }.onFailure { Log.d(TAG, "isTextFile 文本嗅探失败: ${file.path}", it) }
        .getOrDefault(false)
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