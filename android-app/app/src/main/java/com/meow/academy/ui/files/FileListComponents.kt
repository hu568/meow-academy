package com.meow.academy.ui.files

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.CreateNewFolder
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.data.files.IMAGE_EXTENSIONS
import com.meow.academy.ui.theme.LocalFileTypeColors
import com.meow.academy.ui.theme.LocalThemeExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/** 列表行：图标 + 名称 + 副标题（大小/时间）+ 多选勾选框；选中项整行亮起（Windows 多选风格，喵~） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    entry: FileEntry,
    multiSelect: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    subtitle: String? = null, // 覆盖默认「大小 · 时间」副标题（快捷面板最近/收藏显示相对路径用，喵~）
) {
    val kind = listKind(entry)
    // 选中亮起：primaryContainer 底色铺在 ListItem 容器后面（容器本身置透明露出高亮）
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(
                if (multiSelect) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            if (kind == FileKind.IMAGE) {
                FileThumbnail(path = entry.path, name = entry.name)
            } else {
                Icon(fileIcon(kind), contentDescription = null, tint = fileColor(kind))
            }
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                subtitle ?: if (entry.isDirectory) formatTime(entry.lastModified)
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

/**
 * 图片文件缩略图：Coil 采样加载，失败回退图片图标（喵~）。
 * 尺寸与圆角由 [modifier] 决定（默认 40dp 圆角小图给列表行用；网格视图传 fillMaxWidth 覆盖媒体区）。
 */
@Composable
internal fun FileThumbnail(
    path: String,
    name: String,
    modifier: Modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
) {
    SubcomposeAsyncImage(
        model = File(path),
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
        },
        error = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = fileColor(FileKind.IMAGE),
                )
            }
        },
    )
}

/**
 * 宫格 / 瀑布流网格视图：[columns] 一行列数（宫格 3、瀑布流 2），
 * [card] 为 true 时条目带圆角卡片底（瀑布流样式），false 为宫格扁平样式。
 * 点击 / 长按 / 多选行为与列表行一致，由调用方注入（喵~）。
 */
@Composable
fun FileEntryGrid(
    entries: List<FileEntry>,
    columns: Int,
    card: Boolean,
    multiSelect: Boolean,
    selection: Set<String>,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(if (card) 10.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(if (card) 10.dp else 2.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FileGridCell(
                entry = entry,
                card = card,
                multiSelect = multiSelect,
                selected = entry.path in selection,
                onClick = { onClick(entry) },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}

/**
 * 网格单格：媒体区（图片显示缩略图，其余显示类型大图标）+ 名称 + 大小/时间。
 * [card] 瀑布流卡片样式（圆角底色 + 大图标 + 完整副标题）；false 为宫格扁平紧凑样式。
 * 多选时媒体区右上角浮勾选框，与列表行的勾选语义一致（喵~）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: FileEntry,
    card: Boolean,
    multiSelect: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kind = listKind(entry)
    // 选中亮起：Windows 多选风格高亮底色（与列表行同色），卡片态盖过默认 surfaceContainer（喵~）
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        card -> MaterialTheme.colorScheme.surfaceContainer
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (card) 16.dp else 8.dp))
            .background(containerColor)
            .then(
                if (multiSelect) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .padding(
                horizontal = if (card) 12.dp else 6.dp,
                vertical = if (card) 12.dp else 8.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 媒体区：固定高度保证同行格子等高对齐（喵~）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (card) 96.dp else 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == FileKind.IMAGE) {
                FileThumbnail(
                    path = entry.path,
                    name = entry.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (card) 12.dp else 8.dp)),
                )
            } else {
                Icon(
                    fileIcon(kind),
                    contentDescription = null,
                    tint = fileColor(kind),
                    modifier = Modifier.size(if (card) 56.dp else 40.dp),
                )
            }
            if (multiSelect) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                entry.isDirectory -> formatTime(entry.lastModified)
                card -> "${formatSize(entry.size)} · ${formatTime(entry.lastModified)}"
                else -> formatSize(entry.size)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
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

    val extras = LocalThemeExtras.current

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
                            segment.navigable -> extras.quickBarColor ?: MaterialTheme.colorScheme.primary
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
 * 复制 / 移动目标目录选择器（Windows 资源管理器风格，喵~）：
 * 面包屑展示完整绝对路径，与主界面同款语义——可用根（filesDir / App 外部目录）内的段可点跳级，
 * 根外系统前缀灰显仅展示；行尾编辑按钮可直接输入路径跳转，工作区上一级的 filesDir 也可选。
 * 列表只列当前目录的直接子文件夹（点击逐级进入），底部确认按钮把「当前所处目录」作为目标；
 * 面包屑行尾附新建文件夹（建完自动进入）。起始停在 [initialDir]（文件管理页当前目录）。
 * [lockedDirs] 为本次操作的源目录集合：其自身与子树不可作目标（防把文件夹移动/复制进自身），
 * 列表中灰显禁点；对话框内系统返回键先逐级回上级，退到可用根再按才关闭。
 */
@Composable
fun TargetDirPicker(
    repository: FileRepository,
    title: String,
    confirmLabel: String,
    lockedDirs: List<String>,
    initialDir: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentDir by remember { mutableStateOf(File(initialDir)) }
    var editing by remember { mutableStateOf(false) }
    // null = 加载中；只列直接子文件夹（隐藏目录跳过，与列表页过滤规则一致，喵~）
    var children by remember(currentDir) { mutableStateOf<List<File>?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val breadcrumbScroll = rememberScrollState()
    val extras = LocalThemeExtras.current

    fun isLocked(dir: File?): Boolean {
        if (dir == null) return false
        val dp = dir.absolutePath.trimEnd('/')
        return lockedDirs.any { locked ->
            val lp = File(locked).absolutePath.trimEnd('/')
            dp == lp || dp.startsWith("$lp/")
        }
    }

    // 进入目录后异步加载直接子文件夹
    LaunchedEffect(currentDir) {
        val dir = currentDir
        val result = withContext(Dispatchers.IO) {
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith('.') }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
        if (currentDir == dir) children = result
    }

    // 面包屑自动滚到最右，保证当前目录段可见（与主界面路径面包屑同款处理，喵~）
    LaunchedEffect(currentDir) {
        withFrameNanos { }
        breadcrumbScroll.scrollTo(breadcrumbScroll.maxValue)
    }

    // 系统返回键：对话框内先逐级回上级（限定可用根内），退到根后不拦（走 AlertDialog 默认关闭）
    BackHandler(enabled = currentDir != null && !editing) {
        val parent = currentDir?.parentFile
        if (parent != null && repository.isWithinRoot(parent.absolutePath)) {
            currentDir = parent
        } else {
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (editing) {
                    // ── 路径输入态：直接键入目标路径（可用根内任意目录，含工作区上级 filesDir，喵~） ──
                    PickerPathEditField(
                        repository = repository,
                        currentPath = currentDir.absolutePath,
                        lockedDirs = lockedDirs,
                        onNavigate = {
                            editing = false
                            currentDir = it
                        },
                        onCancel = { editing = false },
                    )
                } else {
                    // ── 面包屑（完整绝对路径，与主界面同款语义）+ 路径编辑 + 新建文件夹 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val cur = currentDir
                        val segments = remember(cur) {
                            buildBreadcrumbSegments(cur.absolutePath) { p -> repository.isWithinRoot(p) }
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .horizontalScroll(breadcrumbScroll)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            segments.forEachIndexed { index, segment ->
                                if (index != 0) {
                                    Text(
                                        " › ",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                BreadcrumbCrumb(
                                    label = segment.label,
                                    isCurrent = index == segments.lastIndex,
                                    enabled = segment.navigable,
                                    tint = if (segment.navigable) {
                                        extras.quickBarColor ?: MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) { currentDir = File(segment.path) }
                            }
                        }
                        // 路径编辑：输入完整路径回车跳转（主界面面包屑同款入口，喵~）
                        IconButton(onClick = { editing = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "输入路径",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 新建文件夹：建完自动进入（Windows 选择对话框同款能力，喵~）
                        IconButton(onClick = { showNewFolder = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = "新建文件夹",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // ── 当前目录的直接子文件夹 ──
                val list = children
                when {
                    list == null -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    list.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "空文件夹（可直接选此处）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(list, key = { it.absolutePath }) { dir ->
                            val locked = isLocked(dir)
                            val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (locked) Modifier else Modifier.clickable { currentDir = dir }),
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = if (locked) disabledColor else MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        dir.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (locked) disabledColor else Color.Unspecified,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(currentDir.absolutePath) },
                enabled = !isLocked(currentDir),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showNewFolder) {
        NewNameDialog(
            title = "新建文件夹",
            confirmLabel = "创建",
            initialValue = "",
            onConfirm = { name ->
                showNewFolder = false
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        val dir = File(currentDir, name)
                        dir.isDirectory || dir.mkdir()
                    }
                    if (created) currentDir = File(currentDir, name)
                }
            },
            onDismiss = { showNewFolder = false },
        )
    }
}

/**
 * 面包屑单段：[isCurrent] 当前目录高亮加粗不可点；[enabled] 为 false 灰显禁点（可用根外的前缀段，喵~）。
 * [tint] 非当前段的颜色（可点段主色/快捷色，禁点段弱化灰）。
 */
@Composable
private fun BreadcrumbCrumb(
    label: String,
    isCurrent: Boolean,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (isCurrent) MaterialTheme.colorScheme.onSurface else tint,
        fontWeight = if (isCurrent) FontWeight.Bold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = !isCurrent && enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * 选择器内嵌路径输入行：起始为当前路径（光标在末尾，自动聚焦拉起键盘），
 * 回车校验后跳转——必须是存在的目录、在可用根（filesDir / App 外部目录）内、
 * 且不在 [lockedDirs] 源目录子树中；失败红字提示并留在输入态（喵~）。
 */
@Composable
private fun PickerPathEditField(
    repository: FileRepository,
    currentPath: String,
    lockedDirs: List<String>,
    onNavigate: (File) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(currentPath, TextRange(currentPath.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    fun submit() {
        val raw = text.text.trim()
        if (raw.isEmpty()) return
        scope.launch {
            val normalized = withContext(Dispatchers.IO) {
                repository.normalizeNavigationPath(raw)?.takeIf { repository.isWithinRoot(it) }
            }
            val locked = normalized?.let { n ->
                val np = n.trimEnd('/')
                lockedDirs.any { locked ->
                    val lp = File(locked).absolutePath.trimEnd('/')
                    np == lp || np.startsWith("$lp/")
                }
            } == true
            when {
                normalized == null -> error = "路径不存在或不是文件夹"
                locked -> error = "不能选本次操作的源目录内部"
                else -> onNavigate(File(normalized))
            }
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            error = null
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        label = { Text("输入路径") },
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        trailingIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "取消输入")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
    )
}
