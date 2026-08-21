package com.meow.academy.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.ui.chat.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 编辑页两种视图模式 */
private enum class EditorMode { EDIT, PREVIEW }

/** 撤销/恢复历史最大步数 */
private const val MAX_UNDO_HISTORY = 100

/**
 * 文件编辑器（全屏覆盖页）：编辑 / Markdown 预览切换 + 保存 + 撤销/恢复 + 重命名 + 更多菜单。
 *
 * 由 FilesScreen 在点击文本文件时全屏打开；保存成功后回调 [onSaved]，
 * 由调用方负责刷新列表并关闭本页（喵~）。
 *
 * @param file 目标文件条目（name / path / isDirectory / size / lastModified）
 * @param repository 文件数据层（读/写 UTF-8、文本判定、Markdown 判定）
 * @param onBack 返回上一页
 * @param onSaved 保存成功回调（FilesScreen 刷新列表并关闭编辑页）
 * @param onRenamed 重命名成功回调（父级同步最新 FileEntry，保持编辑页不关闭）
 * @param onDeleted 删除成功回调（父级关闭编辑页并刷新列表）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    file: FileEntry,
    repository: FileRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onRenamed: (FileEntry) -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 当前正在编辑的文件：重命名后原地更新，避免重读内容 / 重置撤销历史（喵~）
    var currentFile by remember { mutableStateOf(file) }
    // 已加载内容的路径：重命名时手动同步，防止 LaunchedEffect 因路径变化重复读盘
    var loadedPath by remember { mutableStateOf<String?>(null) }

    // 用 TextFieldValue 而非 String：编辑区需要 selection（光标位置）做自动滚动（喵~）
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var isLoading by remember { mutableStateOf(true) }
    // 非 null 表示不可预览（二进制 / 超大文件），值为提示文案
    var previewError by remember { mutableStateOf<String?>(null) }
    // 非 null 表示可预览但不可编辑（超大 HTML），值为提示文案
    var editBlocked by remember { mutableStateOf<String?>(null) }
    // HTML 是否已读入内存（切到编辑模式才加载）；未加载时预览走磁盘 WebView，避免大文件读内存
    var htmlContentLoaded by remember { mutableStateOf(false) }
    // HTML 默认停在预览模式（WebView 渲染），与文本文件默认编辑统一在同一个编辑器界面（喵~）
    var mode by remember {
        mutableStateOf(if (repository.isHtmlFile(file.name)) EditorMode.PREVIEW else EditorMode.EDIT)
    }

    // 撤销 / 恢复栈（每次编辑入栈，上限 MAX_UNDO_HISTORY）
    var undoStack by remember { mutableStateOf(emptyList<TextFieldValue>()) }
    var redoStack by remember { mutableStateOf(emptyList<TextFieldValue>()) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // 系统返回键：v1 从简，不弹「未保存」确认，直接返回
    BackHandler { onBack() }

    // 父级（FilesScreen）因重命名回调替换了 file 时，同步本地 currentFile 并触发重新加载
    LaunchedEffect(file.path) {
        if (file.path != currentFile.path) {
            currentFile = file
            loadedPath = null
        }
    }

    // 进入时做大小/二进制兜底检查并读取内容。
    // HTML 例外：预览走 WebView 不读内存（不受 1MB 限制），内容切到编辑态再惰性加载（喵~）
    // 重命名场景 loadedPath 已同步为最新路径，这里直接跳过，避免白读一次盘。
    LaunchedEffect(currentFile.path) {
        if (loadedPath == currentFile.path) return@LaunchedEffect
        loadedPath = currentFile.path
        isLoading = true
        previewError = null
        editBlocked = null
        htmlContentLoaded = false
        val diskFile = File(currentFile.path)
        val isHtml = repository.isHtmlFile(currentFile.name)
        previewError = when {
            isHtml -> null
            !repository.isTextFile(diskFile) -> "无法预览（二进制或未知格式）"
            diskFile.length() > FileRepository.TEXT_PREVIEW_LIMIT -> "文件过大，请用终端打开"
            else -> null
        }
        editBlocked = if (isHtml && diskFile.length() > FileRepository.TEXT_PREVIEW_LIMIT) {
            "文件过大，请用终端打开"
        } else {
            null
        }
        if (previewError == null && !isHtml) {
            fieldValue = TextFieldValue(withContext(Dispatchers.IO) { repository.readText(currentFile.path) })
        }
        undoStack = emptyList()
        redoStack = emptyList()
        isLoading = false
    }

    // HTML 编辑态惰性加载：进入编辑模式时才读盘，避免预览页/大文件白白读入内存（喵~）
    LaunchedEffect(mode) {
        if (mode == EditorMode.EDIT &&
            repository.isHtmlFile(currentFile.name) &&
            editBlocked == null &&
            fieldValue.text.isEmpty()
        ) {
            fieldValue = TextFieldValue(withContext(Dispatchers.IO) { repository.readText(currentFile.path) })
            htmlContentLoaded = true
        }
    }

    // 保存：直接 writeText + onSaved；失败 Toast 提示并保留内容
    fun save() {
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { repository.writeText(currentFile.path, fieldValue.text) }
            }.isSuccess
            if (ok) {
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                onSaved()
            } else {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun undo() {
        val last = undoStack.lastOrNull() ?: return
        redoStack = redoStack + fieldValue
        fieldValue = last
        undoStack = undoStack.dropLast(1)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = undoStack + fieldValue
        fieldValue = next
        redoStack = redoStack.dropLast(1)
    }

    // 重命名：文件名（不含后缀）+ 后缀两个输入框，成功后原地更新 currentFile 并通知父级
    fun renameFile(base: String, ext: String) {
        val normalizedExt = if (ext.isNotEmpty() && !ext.startsWith(".")) ".$ext" else ext
        val newName = if (normalizedExt.isEmpty()) base else "$base$normalizedExt"
        if (newName == currentFile.name) {
            showRenameDialog = false
            return
        }
        if (!repository.isValidName(newName)) {
            Toast.makeText(context, "文件名不合法", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { repository.rename(currentFile.path, newName) }
            if (ok) {
                val newPath = File(currentFile.path).parentFile?.resolve(newName)?.absolutePath
                    ?: currentFile.path
                val newFile = currentFile.copy(
                    name = newName,
                    path = newPath,
                    lastModified = File(newPath).lastModified(),
                )
                currentFile = newFile
                loadedPath = newFile.path // 重命名不重读内容，也不清撤销历史（喵~）
                showRenameDialog = false
                Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                onRenamed(newFile)
            } else {
                Toast.makeText(context, "重命名失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 删除当前文件（更多菜单入口），成功后回调父级关闭编辑页
    fun deleteFile() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { repository.delete(currentFile.path) }
            showDeleteConfirm = false
            if (ok) {
                onDeleted()
            } else {
                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        // 顶栏：返回 + 操作按钮（标题用浮动滚动条展示，喵~）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { undo() },
                enabled = undoStack.isNotEmpty() && !isLoading,
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
            }
            IconButton(
                onClick = { redo() },
                enabled = redoStack.isNotEmpty() && !isLoading,
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "恢复")
            }
            IconButton(
                onClick = { save() },
                // HTML 未切过编辑模式时内容尚未读入内存，禁用保存防止覆盖成空文件（喵~）
                enabled = !isLoading && previewError == null && editBlocked == null &&
                    (!repository.isHtmlFile(currentFile.name) || htmlContentLoaded),
            ) {
                Icon(Icons.Filled.Save, contentDescription = "保存")
            }
            IconButton(
                onClick = {
                    mode = if (mode == EditorMode.EDIT) EditorMode.PREVIEW else EditorMode.EDIT
                },
                enabled = !isLoading && previewError == null,
            ) {
                if (mode == EditorMode.EDIT) {
                    Icon(Icons.Filled.Visibility, contentDescription = "预览")
                } else {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            showMoreMenu = false
                            showRenameDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMoreMenu = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
        }

        // 浮动文件名滚动条（向左滚动循环，独立占一行空隙，不遮下方编辑区圆角框，喵~）
        MarqueeTitle(
            text = currentFile.name,
            onClick = { showRenameDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )

        // 内容区：加载中 / 不可预览 / 编辑 / 预览（浮动标题条在上方独立一行，不遮编辑区圆角框，喵~）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
        // 内容区：加载中 / 不可预览 / 编辑 / 预览
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            previewError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = previewError.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            mode == EditorMode.EDIT -> {
                if (editBlocked != null) {
                    // 大 HTML：可预览但内容不读入内存，编辑区直接提示用终端（喵~）
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = editBlocked.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                // 多行编辑器（修复「键盘弹出后跳顶、光标不跟随」BUG 喵~）：
                // 旧实现 OutlinedTextField + fillMaxSize 固定高度靠内部自滚动，键盘弹出时
                // imePadding 使其高度骤减，内部滚动位置被重置到顶部，且光标不会滚回可视区。
                // 新实现：BasicTextField（能拿到 onTextLayout 光标排版信息）+ 官方
                // OutlinedTextFieldDefaults.DecorationBox/ContainerBox 复刻原版描边框外观；
                // 高度随内容铺开、自身不滚动，由外层 verticalScroll 统一滚动 —— 键盘弹出只
                // 压缩视口、不重置滚动位置；再监听光标/视口变化，把光标行滚进视口上半部。
                val editScroll = rememberScrollState()
                val interactionSource = remember { MutableInteractionSource() }
                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                var viewportHeightPx by remember { mutableIntStateOf(0) }
                // 滚动容器在窗口中的 y（配合文本区窗口坐标算内容内偏移）
                var containerY by remember { mutableIntStateOf(0) }
                // 文本区在滚动内容中的纵向偏移（用于把 TextLayoutResult 坐标换算成滚动坐标）
                var fieldOffsetY by remember { mutableIntStateOf(0) }

                // 光标或视口（键盘弹出/收起）变化时，把光标行滚进视口上半部
                LaunchedEffect(fieldValue.selection, textLayout, viewportHeightPx) {
                    val layout = textLayout ?: return@LaunchedEffect
                    if (viewportHeightPx <= 0) return@LaunchedEffect
                    val offset = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
                    val line = layout.getLineForOffset(offset)
                    val lineTop = fieldOffsetY + layout.getLineTop(line).toInt()
                    val lineBottom = fieldOffsetY + layout.getLineBottom(line).toInt()
                    val viewStart = editScroll.value
                    val comfortEnd = viewStart + viewportHeightPx / 2
                    // 已完整落在视口上半部则不打扰；否则滚到视口约 1/4 高度处
                    if (lineTop < viewStart || lineBottom > comfortEnd) {
                        editScroll.animateScrollTo((lineTop - viewportHeightPx / 4).coerceAtLeast(0))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        // onSizeChanged 放在 verticalScroll 外层：测得的是应用 imePadding 后的视口高度
                        .onSizeChanged { viewportHeightPx = it.height }
                        .onGloballyPositioned { containerY = it.positionInWindow().y.toInt() }
                        .verticalScroll(editScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { newValue ->
                            if (newValue != fieldValue) {
                                undoStack = (undoStack + fieldValue).takeLast(MAX_UNDO_HISTORY)
                                redoStack = emptyList()
                                fieldValue = newValue
                            }
                        },
                        // BasicTextField 不像 OutlinedTextField 会自动套主题文字色，
                        // 必须显式给 onSurface，否则暗色模式下文字是黑的（喵~）
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        onTextLayout = { textLayout = it },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                    ) { innerTextField ->
                        // 官方 DecorationBox + ContainerBox：与 OutlinedTextField 同款描边框
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = fieldValue.text,
                            innerTextField = {
                                Box(
                                    Modifier.onGloballyPositioned { coords ->
                                        // 相对滚动容器的偏移 = 窗口坐标差 + 已滚过的距离
                                        fieldOffsetY = coords.positionInWindow().y.toInt() -
                                            containerY + editScroll.value
                                    },
                                ) { innerTextField() }
                            },
                            enabled = true,
                            isError = false,
                            singleLine = false,
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
                }
            }
            else -> {
                when {
                    repository.isMarkdown(currentFile.name) -> {
                        // Markdown：TextView 按内容高度铺开，由外层 Compose verticalScroll 统一滚动，
                        // 避免 TextView 内部 ScrollingMovementMethod 自滚动导致「无惯性、直接刹车」。
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            MarkdownText(fieldValue.text, modifier = Modifier.fillMaxWidth(), streaming = false)
                        }
                    }
                    repository.isHtmlFile(currentFile.name) -> {
                        // HTML：未进入过编辑时直接渲染磁盘文件（不读内存，大文件也能预览）；
                        // 已加载/编辑过后渲染内存内容，能看到未保存的修改（喵~）
                        HtmlWebView(
                            file = File(currentFile.path),
                            content = if (htmlContentLoaded) fieldValue.text else null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        // 普通文本：等宽 + 外层 verticalScroll 滚动，顶部对齐
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            Text(fieldValue.text, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        }
    }

    // ── 对话框：重命名（标题点击 / 更多菜单）与删除确认 ──
    if (showRenameDialog) {
        RenameFileDialog(
            fileName = currentFile.name,
            onConfirm = { base, ext -> renameFile(base, ext) },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定删除「${currentFile.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { deleteFile() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 重命名对话框：文件名（不含后缀）与后缀分两个输入框。
 * 后缀输入框自动补点：用户填 `md` 会归一化为 `.md`（喵~）。
 */
@Composable
fun RenameFileDialog(
    fileName: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialBase, initialExt) = remember(fileName) { splitFileName(fileName) }
    var base by remember(fileName) { mutableStateOf(initialBase) }
    var ext by remember(fileName) { mutableStateOf(initialExt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    label = { Text("文件名（不含后缀）") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ext,
                    onValueChange = { ext = it },
                    singleLine = true,
                    label = { Text("后缀（如 .md）") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(base.trim(), ext.trim()) },
                enabled = base.isNotBlank() &&
                    '/' !in base && '\u0000' !in base &&
                    '/' !in ext && '\u0000' !in ext,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把文件名拆成「不带后缀的主名」+「含点的后缀」（.gitignore 这类隐藏文件整体算主名，喵~） */
private fun splitFileName(name: String): Pair<String, String> {
    val idx = name.lastIndexOf('.')
    return if (idx > 0) name.substring(0, idx) to name.substring(idx) else name to ""
}

/**
 * 浮动文件名滚动条（喵~）。
 *
 * 文字比容器宽时向左循环滚动；否则静态居中展示。
 * 点击可触发重命名。独立占据顶栏与编辑区之间的一行空隙，不遮编辑区圆角框。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun MarqueeTitle(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.titleMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val measured = remember(text, textStyle, textMeasurer) {
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
    val singleTextWidthPx = measured.size.width
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val scrollDistancePx = singleTextWidthPx
    val shouldScroll = containerWidthPx > 0 && singleTextWidthPx > containerWidthPx

    val transition = rememberInfiniteTransition(label = "marqueeTitle")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (scrollDistancePx > 0) {
                    (scrollDistancePx * 10).coerceIn(2500, 15000)
                } else {
                    3000
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marqueeScroll",
    )

    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .onSizeChanged { containerWidthPx = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (shouldScroll) {
            Text(
                text = text + text,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .offset {
                        IntOffset(x = -(progress * scrollDistancePx).roundToInt(), y = 0)
                    },
            )
        } else {
            Text(
                text = text,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}
