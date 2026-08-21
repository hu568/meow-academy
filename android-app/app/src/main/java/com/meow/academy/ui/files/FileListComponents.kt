package com.meow.academy.ui.files

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.data.files.FileRoot
import com.meow.academy.ui.theme.LocalFileTypeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 文件类型分类（用于列表图标 / 颜色 / 打开判定） */
enum class FileKind { FOLDER, MARKDOWN, TEXT, IMAGE, AUDIO, VIDEO, PDF, ARCHIVE, CODE, JSON, HTML, DATABASE, APK, BINARY, LARGE_TEXT }

/**
 * 按名称（扩展名）轻量分类，不读文件内容。
 * 用于列表每项图标与颜色展示（避免为每个条目做文件探测）。
 */
fun fileKindOf(name: String, isDirectory: Boolean): FileKind {
    if (isDirectory) return FileKind.FOLDER
    return when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> FileKind.MARKDOWN
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic", "avif" -> FileKind.IMAGE
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mid", "midi" -> FileKind.AUDIO
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "m3u8" -> FileKind.VIDEO
        "pdf" -> FileKind.PDF
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "jar" -> FileKind.ARCHIVE
        "kt", "java", "ts", "tsx", "js", "jsx", "py", "go", "rs", "c", "cpp", "h", "hpp", "swift",
        "sql", "sh", "bat", "ps1", "rb", "php", "scala", "dart", "lua", "css", "vim" -> FileKind.CODE
        "json", "jsonl", "jsonc" -> FileKind.JSON
        "html", "htm", "xhtml", "xml" -> FileKind.HTML
        "db", "sqlite", "sqlite3", "db3" -> FileKind.DATABASE
        "apk" -> FileKind.APK
        "txt", "log", "csv", "yaml", "yml", "toml", "env", "properties", "ini", "conf" -> FileKind.TEXT
        else -> FileKind.BINARY
    }
}

/** 轻量图标分类（列表展示用，不读文件内容） */
fun listKind(entry: FileEntry): FileKind = fileKindOf(entry.name, entry.isDirectory)

/**
 * 精确分类（用于点击打开时的判定）：会读取小文件内容做二进制嗅探。
 * FOLDER 以外的取值决定打开行为（文本可编辑 / HTML WebView 预览 / 超大文本转终端 / 二进制不可预览）。
 */
fun openKind(file: File, repository: FileRepository): FileKind = when {
    file.isDirectory -> FileKind.FOLDER
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

/** 列表行：图标 + 名称 + 副标题（大小/时间）+ 多选勾选框 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    entry: FileEntry,
    multiSelect: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kind = listKind(entry)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (multiSelect) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            ),
        leadingContent = {
            Icon(fileIcon(kind), contentDescription = null, tint = fileColor(kind))
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                if (entry.isDirectory) formatTime(entry.lastModified)
                else "${formatSize(entry.size)} · ${formatTime(entry.lastModified)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (multiSelect) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        },
    )
}

/** 面包屑分段：label 展示名 + 点击跳转的累计路径 + 是否可点击跳转（可用根外的前缀段仅作展示） */
private data class BreadcrumbSegment(val label: String, val path: String, val navigable: Boolean)

/**
 * 把当前绝对路径按 '/' 逐层拆成面包屑分段（/data/user/0/…/files/workspace/uploads 全层级可见），
 * 位于可用根（filesDir / 外部目录）内的段可点击跳转，根外系统前缀段灰色仅展示。
 * 直接拆绝对路径、不依赖任何根基准的前缀匹配，天然无「假段」（喵~）。
 */
private fun buildBreadcrumbSegments(path: String, isNavigable: (String) -> Boolean): List<BreadcrumbSegment> {
    val normalized = File(path).absolutePath
    val segments = mutableListOf<BreadcrumbSegment>()
    var current = ""
    normalized.split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = "$current/$part"
        segments += BreadcrumbSegment(part, current, isNavigable(current))
    }
    Log.d("FilesNav", "breadcrumb: path=[$normalized] segments=${segments.joinToString("|") { it.label }}")
    return segments
}

/**
 * 绝对路径面包屑：完整路径逐层展示（/data/user/0/…/files/workspace/uploads），
 * 可用根内的段可点击跳转，根外系统前缀段灰色仅展示；点击右侧编辑按钮可输入完整路径回车跳转。
 * 放在文件管理上方，替代原来的纯文本路径展示（喵~）。
 */
@Composable
fun EditableBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    isNavigable: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var editing by remember(path) { mutableStateOf(false) }
    // 用 TextFieldValue 而非 String：编辑框需要 selection（光标位置）做自动滚动（喵~）
    var draft by remember(path) { mutableStateOf(TextFieldValue(path)) }
    val scrollState = rememberScrollState()

    // 路径变化时自动滚到最右端，保证当前目录段可见
    LaunchedEffect(path) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    // 编辑态下系统返回键优先退出编辑，而不是返回上级目录
    BackHandler(enabled = editing) { editing = false }

    if (editing) {
        PathEditField(
            value = draft,
            onValueChange = { draft = it },
            onGo = {
                val target = draft.text.trim()
                onNavigate(target)
                editing = false
            },
            onCancel = { editing = false },
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    } else {
        val segments = remember(path, isNavigable) { buildBreadcrumbSegments(path, isNavigable) }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                segments.forEachIndexed { index, segment ->
                    Text(
                        text = segment.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            index == segments.lastIndex -> MaterialTheme.colorScheme.onSurface
                            segment.navigable -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant // 根外前缀：弱化仅展示
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (segment.navigable) {
                                    Modifier.clickable { onNavigate(segment.path) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    if (index != segments.lastIndex) {
                        Text(
                            text = "/",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 1.dp),
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    editing = true
                    // 光标直接置于路径末尾（喵~）：TextFieldValue 默认 selection 在开头，
                    // 显式给 TextRange(path.length)，配合 PathEditField 的光标跟随滚动直达最右端
                    draft = TextFieldValue(path, TextRange(path.length))
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑路径",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 路径编辑输入框（修复「拖动光标手柄文字不自滚动」BUG 喵~）：
 * 旧实现 OutlinedTextField(String)：内部自滚动对「拖动选择手柄」不生效，
 * 且外部拿不到 selection 无法自行补偿。
 * 新实现：BasicTextField（能拿到 onTextLayout 光标排版信息与 selection）
 * + 官方 OutlinedTextFieldDefaults.DecorationBox/ContainerBox 复刻原版描边框外观；
 * 文本区自身不限宽、由外层 horizontalScroll 统一滚动，监听光标变化把光标滚进视口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PathEditField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onGo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val interactionSource = remember { MutableInteractionSource() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 文本视口宽度（描边框内、图标之外的可视区域）
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 光标贴近视口边缘时的留白
    val edgeMarginPx = with(density) { 16.dp.toPx() }
    // 进入编辑态自动聚焦并拉起键盘：配合外部传入的「光标在末尾」selection 直接可输入（喵~）
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // 光标/选区（含拖动选择手柄 / 键盘移动 / 输入）变化时，把选区两端滚进可视区
    LaunchedEffect(value.selection, textLayout, viewportWidthPx) {
        val layout = textLayout ?: return@LaunchedEffect
        if (viewportWidthPx <= 0 || value.text.isEmpty()) return@LaunchedEffect
        // 选区两端都要可见（拖动任一手柄都能跟随）；折叠选区时两端重合即光标位置
        val textLength = value.text.length
        val startX = layout.getHorizontalPosition(value.selection.min.coerceIn(0, textLength), true).coerceAtLeast(0f)
        val endX = layout.getHorizontalPosition(value.selection.max.coerceIn(0, textLength), true).coerceAtLeast(0f)
        val viewStart = scrollState.value.toFloat()
        val viewEnd = viewStart + viewportWidthPx
        val target = when {
            startX < viewStart -> startX - edgeMarginPx // 左端出视口左侧：向左滚
            endX > viewEnd - edgeMarginPx -> endX - viewportWidthPx + edgeMarginPx // 右端出视口右侧：向右滚
            else -> return@LaunchedEffect // 已可见：不打扰
        }
        scrollState.animateScrollTo(target.roundToInt().coerceIn(0, scrollState.maxValue))
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        onTextLayout = { textLayout = it },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier
            .defaultMinSize(minHeight = 56.dp)
            .focusRequester(focusRequester),
    ) { innerTextField ->
        // 官方 DecorationBox + ContainerBox：与 OutlinedTextField 同款描边框
        OutlinedTextFieldDefaults.DecorationBox(
            value = value.text,
            innerTextField = {
                Box(
                    Modifier
                        .onSizeChanged { viewportWidthPx = it.width }
                        .horizontalScroll(scrollState),
                ) { innerTextField() }
            },
            label = { Text("输入路径") },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = "取消编辑")
                }
            },
            enabled = true,
            isError = false,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            colors = OutlinedTextFieldDefaults.colors(),
            contentPadding = OutlinedTextFieldDefaults.contentPadding(),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = interactionSource,
                )
            },
        )
    }
}

/**
 * 快捷栏（替代原根目录切换）：根目录 + 当前根下的一级子目录，一键跳转目标文件夹。
 * 横向可滚动；当前所在目录对应的项高亮（喵~）。
 */
@Composable
fun ShortcutBar(
    shortcuts: List<FileShortcut>,
    currentPath: String,
    onNavigate: (FileShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shortcuts.forEach { shortcut ->
            FilterChip(
                selected = currentPath == shortcut.path,
                onClick = { onNavigate(shortcut) },
                label = { Text(shortcut.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** 右上角排序菜单：模式选择 + 升降序切换 */
@Composable
fun SortMenu(
    mode: FileSortMode,
    ascending: Boolean,
    onSort: (FileSortMode, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (mode == FileSortMode.NAME) "名称${if (ascending) " ↑" else " ↓"}" else "名称") },
                onClick = {
                    onSort(FileSortMode.NAME, if (mode == FileSortMode.NAME) !ascending else true)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(if (mode == FileSortMode.SIZE) "大小${if (ascending) " ↑" else " ↓"}" else "大小") },
                onClick = {
                    onSort(FileSortMode.SIZE, if (mode == FileSortMode.SIZE) !ascending else true)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(if (mode == FileSortMode.MODIFIED) "修改时间${if (ascending) " ↑" else " ↓"}" else "修改时间") },
                onClick = {
                    onSort(FileSortMode.MODIFIED, if (mode == FileSortMode.MODIFIED) !ascending else true)
                    expanded = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (ascending && mode != FileSortMode.DEFAULT) "切换为降序" else "切换为升序") },
                onClick = {
                    onSort(
                        if (mode == FileSortMode.DEFAULT) FileSortMode.NAME else mode,
                        !ascending,
                    )
                    expanded = false
                },
            )
        }
    }
}

/** 新建文件 / 新建文件夹 / 重命名 共用名称输入对话框 */
@Composable
fun NewNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && '/' !in name,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除确认对话框（含递归删除提示 / 批量数量） */
@Composable
fun ConfirmDeleteDialog(
    title: String = "确认删除",
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 底部批量操作栏（多选态）：复制 / 移动 / 删除 / 取消 */
@Composable
fun MultiSelectBar(
    count: Int,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("已选 $count 项", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onCopy, enabled = count > 0) { Text("复制") }
        TextButton(onClick = onMove, enabled = count > 0) { Text("移动") }
        TextButton(onClick = onDelete, enabled = count > 0) { Text("删除") }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

/**
 * 复制 / 移动目标目录选择器：列出当前根下的全部目录（含根目录本身），
 * 点击目录即选择为复制/移动目标（喵~）。
 */
@Composable
fun TargetDirPicker(
    root: FileRoot,
    repository: FileRepository,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val base = remember(root) { repository.resolveRoot(root) }
    var dirs by remember(root) { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember(root) { mutableStateOf(true) }

    // 递归收集根下全部目录（跳过隐藏目录）
    LaunchedEffect(base) {
        if (base == null) { loading = false; return@LaunchedEffect }
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            result += dir
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory && !child.name.startsWith('.')) walk(child)
            }
        }
        withContext(Dispatchers.IO) { walk(base) }
        dirs = result
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标目录") },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(dirs, key = { it.absolutePath }) { dir ->
                        val relative = try {
                            dir.absolutePath.removePrefix(base?.absolutePath ?: "").trimStart('/')
                        } catch (e: Exception) {
                            dir.name
                        }
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(dir.absolutePath) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(if (relative.isEmpty()) "（根目录）" else relative, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
