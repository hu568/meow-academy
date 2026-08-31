package com.meow.academy.ui.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.data.files.IMAGE_EXTENSIONS
import com.meow.academy.ui.theme.LocalFileTypeColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件类型分类（用于列表图标 / 颜色 / 打开判定） */
enum class FileKind { FOLDER, MARKDOWN, TEXT, IMAGE, AUDIO, VIDEO, PDF, ARCHIVE, CODE, JSON, HTML, DATABASE, APK, BINARY, LARGE_TEXT }

/**
 * 按名称（扩展名）轻量分类，不读文件内容。
 * 用于列表每项图标与颜色展示（避免为每个条目做文件探测）。
 */
fun fileKindOf(name: String, isDirectory: Boolean): FileKind {
    if (isDirectory) return FileKind.FOLDER
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTENSIONS -> FileKind.IMAGE
        ext in setOf("md", "markdown") -> FileKind.MARKDOWN
        ext in setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mid", "midi") -> FileKind.AUDIO
        ext in setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "m3u8") -> FileKind.VIDEO
        ext == "pdf" -> FileKind.PDF
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "jar") -> FileKind.ARCHIVE
        ext in setOf(
            "kt", "java", "ts", "tsx", "js", "jsx", "py", "go", "rs", "c", "cpp", "h", "hpp", "swift",
            "sql", "sh", "bat", "ps1", "rb", "php", "scala", "dart", "lua", "css", "vim",
        ) -> FileKind.CODE
        ext in setOf("json", "jsonl", "jsonc") -> FileKind.JSON
        ext in setOf("html", "htm", "xhtml", "xml") -> FileKind.HTML
        ext in setOf("db", "sqlite", "sqlite3", "db3") -> FileKind.DATABASE
        ext == "apk" -> FileKind.APK
        ext in setOf("txt", "log", "csv", "yaml", "yml", "toml", "env", "properties", "ini", "conf") -> FileKind.TEXT
        else -> FileKind.BINARY
    }
}

/** 轻量图标分类（列表展示用，不读文件内容） */
fun listKind(entry: FileEntry): FileKind = fileKindOf(entry.name, entry.isDirectory)

/**
 * 精确分类（用于点击打开时的判定）：会读取小文件内容做二进制嗅探。
 * FOLDER 以外的取值决定打开行为（图片浮窗预览 / 文本可编辑 / HTML WebView 预览 / 超大文本转终端 / 二进制不可预览）。
 */
fun openKind(file: File, repository: FileRepository): FileKind = when {
    file.isDirectory -> FileKind.FOLDER
    repository.isImageFile(file.name) -> FileKind.IMAGE
    repository.isMarkdown(file.name) -> FileKind.MARKDOWN
    repository.isHtmlFile(file.name) -> FileKind.HTML
    repository.isTextFile(file) ->
        if (file.length() > FileRepository.TEXT_PREVIEW_LIMIT) FileKind.LARGE_TEXT else FileKind.TEXT
    else -> FileKind.BINARY
}

/** 文件类型图标（喵~） */
fun fileIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.MARKDOWN -> Icons.Filled.Description
    FileKind.TEXT, FileKind.LARGE_TEXT -> Icons.AutoMirrored.Filled.Article
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.AUDIO -> Icons.Filled.MusicNote
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.PDF -> Icons.Filled.PictureAsPdf
    FileKind.ARCHIVE -> Icons.Filled.Archive
    FileKind.CODE -> Icons.Filled.Code
    FileKind.JSON -> Icons.Filled.DataObject
    FileKind.HTML -> Icons.Filled.Html
    FileKind.DATABASE -> Icons.Filled.Storage
    FileKind.APK -> Icons.Filled.Android
    FileKind.BINARY -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** 文件类型图标颜色（随深浅主题切换，喵~） */
@Composable
fun fileColor(kind: FileKind): Color {
    val c = LocalFileTypeColors.current
    return when (kind) {
        FileKind.FOLDER -> c.folder
        FileKind.MARKDOWN -> c.markdown
        FileKind.TEXT, FileKind.LARGE_TEXT -> c.text
        FileKind.IMAGE -> c.image
        FileKind.AUDIO -> c.audio
        FileKind.VIDEO -> c.video
        FileKind.PDF -> c.pdf
        FileKind.ARCHIVE -> c.archive
        FileKind.CODE -> c.code
        FileKind.JSON -> c.json
        FileKind.HTML -> c.html
        FileKind.DATABASE -> c.database
        FileKind.APK -> c.apk
        FileKind.BINARY -> c.binary
    }
}

/** 字节数 → 可读大小（B / KB / MB） */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** epoch millis → "MM-dd HH:mm" */
fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))