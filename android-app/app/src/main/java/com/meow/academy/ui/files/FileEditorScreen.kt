package com.meow.academy.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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

/** EditorMode 的 Saver：存枚举名（String），恢复时 valueOf */
private val EditorModeSaver = Saver<EditorMode, String>(
    save = { it.name },
    restore = { runCatching { EditorMode.valueOf(it) }.getOrNull() },
)

/** List<TextFieldValue> 的 Saver：组合 TextFieldValue.Saver 用于 undo/redo 栈 */
private val TextFieldValueListSaver: Saver<List<TextFieldValue>, Any> = listSaver(
    save = { list: List<TextFieldValue> ->
        with(TextFieldValue.Saver) { list.map { save(it)!! } }
    },
    restore = { list: List<Any> ->
        with(TextFieldValue.Saver) { list.map { restore(it)!! } }
    },
)

/** 撤销/恢复历史最大步数 */
private const val MAX_UNDO_HISTORY = 100

/**
 * 文件编辑器（全屏覆盖页）：编辑 / Markdown 预览切换 + 保存 + 撤销/恢复 + 重命名 + 更多菜单。
 *
 * 由父级（MainScreen）在 [editingPath] 非空时全屏打开；保存成功后回调 [onSaved]，
 * 由调用方负责刷新列表并关闭本页（喵~）。
 *
 * 用 [file] 的 [FileEntry.path] 作为 composable key：编辑态（[fieldValue]、撤销栈、
 * 滚动位置等）跟文件绑定，跨 tab / 跨配置变化时即使 [editingPath] 重新构造 FileEntry，
 * 只要 path 不变，编辑态原地保留（喵~）。
 *
 * @param file 目标文件条目（name / path / isDirectory / size / lastModified）
 * @param repository 文件数据层（读/写 UTF-8、文本判定、Markdown 判定）
 * @param onBack 返回上一页
 * @param onSaved 保存成功回调（父级关闭编辑页并刷新列表）
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
    // 不改为 rememberSaveable——它由父级 file 参数 + LaunchedEffect(file.path) 同步，
    // 跨 tab 恢复时父级从 editingPath 构造 FileEntry 传入，path 一致不会触发重读盘。
    var currentFile by remember { mutableStateOf(file) }
    // 以下 13 个状态改为 rememberSaveable：切走 tab 时 SaveableStateProvider 自动保存，
    // 切回时恢复——实现「编辑/预览位置 + 模式 + 撤销栈」跨 tab 原地保留（喵~）。
    // 路径标识：重命名时手动同步，防止 LaunchedEffect 因路径变化重复读盘
    var loadedPath by rememberSaveable { mutableStateOf<String?>(null) }

    // TextFieldValue（含光标 selection）用官方 Saver 跨 composition 保存
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var previewError by rememberSaveable { mutableStateOf<String?>(null) }
    var editBlocked by rememberSaveable { mutableStateOf<String?>(null) }
    var htmlContentLoaded by rememberSaveable { mutableStateOf(false) }
    // 模式用 EditorModeSaver 存枚举名——这是「切回预览仍停在预览」的关键（喵~）
    var mode by rememberSaveable(stateSaver = EditorModeSaver) {
        mutableStateOf(if (repository.isHtmlFile(file.name)) EditorMode.PREVIEW else EditorMode.EDIT)
    }

    // ── 模式切换滚动保持 ─────────────────────────────────────────────
    // ScrollState 用官方 Saver 存当前值；rememberSaveable 包装后跨 tab 保留位置
    val editScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val previewScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    // Float 原生可存：HTML 预览实时滚动比例 + 模式切换锚点
    var htmlScrollFraction by rememberSaveable { mutableFloatStateOf(0f) }
    var anchorFraction by rememberSaveable { mutableFloatStateOf(0f) }
    // 瞬态：抑制光标自动跟随的 flag（恢复初始 false 即可）
    var suppressCursorFollow by remember { mutableStateOf(false) }
    // 瞬态：本次切回编辑模式后是否还有「恢复滚动」待执行
    var pendingRestore by remember { mutableStateOf(false) }
    // 瞬态：恢复流程结束（释放 suppressCursorFollow）后 +1，强制光标跟随协程补跑一次（喵~）
    var followTick by remember { mutableIntStateOf(0) }
    // 布局瞬态：切回时由 onSizeChanged / onGloballyPositioned / onTextLayout 重新填充
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var fieldOffsetY by remember { mutableIntStateOf(0) }

    // 撤销 / 恢复栈用 listSaver + TextFieldValue.Saver 组合——跨 tab 保留历史
    var undoStack by rememberSaveable(stateSaver = TextFieldValueListSaver) {
        mutableStateOf(emptyList<TextFieldValue>())
    }
    var redoStack by rememberSaveable(stateSaver = TextFieldValueListSaver) {
        mutableStateOf(emptyList<TextFieldValue>())
    }

    // 对话框瞬态：跨 tab 不需要保留
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // ── 换行模式 ─────────────────────────────────────────────────────
    // true=自动换行（默认）；false=不换行，超宽行整行横向滚动查看（喵~）。
    // rememberSaveable：跨 tab 保留用户的换行偏好
    var wrapMode by rememberSaveable { mutableStateOf(true) }
    // 不换行模式的横向滚动位置：跨 tab 保留（ScrollState.Saver 与 editScroll 同款，喵~）
    val editorHScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    // 切换换行/不换行：布局行数变化，垂直/水平滚动重置回起点（光标由跟随逻辑带回，喵~）。
    // 只在用户操作菜单时调用，避免配置变化恢复状态时误重置编辑位置。
    fun toggleWrapMode(target: Boolean) {
        wrapMode = target
        scope.launch {
            editScroll.scrollTo(0)
            editorHScroll.scrollTo(0)
        }
    }

    // 系统返回键：v1 从简，不弹「未保存」确认，直接返回
    BackHandler { onBack() }

    // 切回编辑模式：等编辑区布局就绪后按锚点比例恢复滚动，并把光标同步到恢复位置。
    // 光标同步是关键：光标跟随逻辑即使随后触发，也会发现光标已在视口内，不会把滚动拉回原光标处（喵~）
    // ⚠️ pendingRestore 不能放进 LaunchedEffect 的 key：恢复流程末尾 pendingRestore=false
    // 会触发重组 → key 变化 → 当前协程被取消，导致挂起后的 suppressCursorFollow=false
    // 永远执行不到，光标跟随被永久抑制（喵~）
    LaunchedEffect(mode, textLayout, viewportHeightPx, fieldOffsetY) {
        if (mode != EditorMode.EDIT || !pendingRestore) return@LaunchedEffect
        // 编辑区不渲染（大 HTML 提示 / 不可预览）时无需恢复，直接放行（喵~）
        if (editBlocked != null || previewError != null) {
            pendingRestore = false
            suppressCursorFollow = false
            followTick++
            return@LaunchedEffect
        }
        val layout = textLayout ?: return@LaunchedEffect
        if (viewportHeightPx <= 0) return@LaunchedEffect
        // 文本区尚未被 onGloballyPositioned 定位（fieldOffsetY 仍为 0）时等下一轮触发，避免光标算错（喵~）
        if (fieldOffsetY <= 0) return@LaunchedEffect
        if (editScroll.maxValue <= 0) {
            pendingRestore = false
            suppressCursorFollow = false
            followTick++
            return@LaunchedEffect
        }
        suppressCursorFollow = true
        editScroll.scrollTo((anchorFraction * editScroll.maxValue).roundToInt())
        // 视口顶部滚动坐标换算成文本布局内 y，取该行起始 offset 作为新光标位置（喵~）
        val topLine = layout.getLineForVerticalPosition((editScroll.value - fieldOffsetY).coerceAtLeast(0).toFloat())
        val topOffset = layout.getLineStart(topLine).coerceIn(0, fieldValue.text.length)
        if (fieldValue.selection.start != topOffset || fieldValue.selection.end != topOffset) {
            fieldValue = fieldValue.copy(selection = TextRange(topOffset))
        }
        // 等一帧：让 selection 变化触发的光标跟随协程在 suppressCursorFollow=true 下跳过
        withFrameNanos { }
        // 先解除抑制，再清除待恢复标记：pendingRestore=false 会触发重组把协程取消，
        // 顺序反了的话（pendingRestore=false 在前）下面这行永远执行不到（喵~）
        suppressCursorFollow = false
        // 释放抑制后立刻 +1：把上面「selection 变化时因抑制而跳过」的光标跟随补跑一次（喵~）
        followTick++
        pendingRestore = false
    }

    // 切回预览模式（Markdown / 纯文本）：内容布局就绪后按锚点比例恢复；HTML 由 WebView 内部恢复
    LaunchedEffect(mode, previewScroll.maxValue, anchorFraction) {
        if (mode != EditorMode.PREVIEW || repository.isHtmlFile(currentFile.name)) return@LaunchedEffect
        if (previewScroll.maxValue > 0) {
            previewScroll.scrollTo((anchorFraction * previewScroll.maxValue).roundToInt())
        }
    }

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
        // 换文件后内容完全不同：滚动位置与跨模式锚点一并重置（重命名不重读不重置，喵~）
        editScroll.scrollTo(0)
        previewScroll.scrollTo(0)
        htmlScrollFraction = 0f
        anchorFraction = 0f
        pendingRestore = false
        suppressCursorFollow = false
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

    /** 切换编辑/预览模式，并在切换前记录当前视口比例作为恢复锚点（喵~） */
    fun toggleMode() {
        anchorFraction = when (mode) {
            EditorMode.EDIT ->
                if (editScroll.maxValue > 0) editScroll.value / editScroll.maxValue.toFloat() else 0f
            EditorMode.PREVIEW ->
                if (repository.isHtmlFile(currentFile.name)) htmlScrollFraction
                else if (previewScroll.maxValue > 0) previewScroll.value / previewScroll.maxValue.toFloat() else 0f
        }
        val nextMode = if (mode == EditorMode.EDIT) EditorMode.PREVIEW else EditorMode.EDIT
        suppressCursorFollow = true
        // 只有切回编辑模式才需要执行一次「恢复滚动 + 光标同步」（喵~）
        pendingRestore = nextMode == EditorMode.EDIT
        mode = nextMode
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
                onClick = { toggleMode() },
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
                    // 换行模式切换：当前模式带对勾，点击切换（喵~）
                    DropdownMenuItem(
                        text = { Text("自动换行") },
                        leadingIcon = {
                            if (wrapMode) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            showMoreMenu = false
                            if (!wrapMode) toggleWrapMode(true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("不换行") },
                        leadingIcon = {
                            if (!wrapMode) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            showMoreMenu = false
                            if (wrapMode) toggleWrapMode(false)
                        },
                    )
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
                // editScroll / textLayout / viewportHeightPx / fieldOffsetY 提升到顶层：
                // 模式切换恢复滚动与光标跟随需要共享（喵~）
                val interactionSource = remember { MutableInteractionSource() }
                // 滚动容器在窗口中的 y（配合文本区窗口坐标算内容内偏移）
                var containerY by remember { mutableIntStateOf(0) }

                // ── 垂直光标跟随 ──────────────────────────────────────────
                // 光标或视口（键盘弹出/收起）变化时，把光标行滚进视口上半部。
                // 模式切换恢复滚动期间先跳过，避免刚恢复的位置被光标自动滚动覆盖（喵~）
                // fieldOffsetY 参与触发：键盘弹出/收起时布局偏移更新后再计算，避免用旧值算错跳顶
                LaunchedEffect(fieldValue.selection, textLayout, viewportHeightPx, fieldOffsetY, followTick) {
                    if (suppressCursorFollow) return@LaunchedEffect
                    // 等一帧：让同帧的 onSizeChanged / onGloballyPositioned 布局值先落定（喵~）
                    withFrameNanos { }
                    if (suppressCursorFollow) return@LaunchedEffect
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

                // ── 横向光标跟随（不换行模式，参考 PathEditField 喵~） ──────
                val edgeMarginPx = with(LocalDensity.current) { 16.dp.toPx() }
                var editorViewportWidthPx by remember { mutableIntStateOf(0) }
                LaunchedEffect(fieldValue.selection, textLayout, editorViewportWidthPx, wrapMode) {
                    if (wrapMode) return@LaunchedEffect
                    val layout = textLayout ?: return@LaunchedEffect
                    if (editorViewportWidthPx <= 0 || fieldValue.text.isEmpty()) return@LaunchedEffect
                    val textLength = fieldValue.text.length
                    val startX = layout.getHorizontalPosition(
                        fieldValue.selection.min.coerceIn(0, textLength), true,
                    ).coerceAtLeast(0f)
                    val endX = layout.getHorizontalPosition(
                        fieldValue.selection.max.coerceIn(0, textLength), true,
                    ).coerceAtLeast(0f)
                    val viewStart = editorHScroll.value.toFloat()
                    val viewEnd = viewStart + editorViewportWidthPx
                    val target = when {
                        startX < viewStart -> startX - edgeMarginPx
                        endX > viewEnd - edgeMarginPx -> endX - editorViewportWidthPx + edgeMarginPx
                        else -> return@LaunchedEffect
                    }
                    editorHScroll.animateScrollTo(target.roundToInt().coerceIn(0, editorHScroll.maxValue))
                }

                // 编辑器 DecorationBox 内容内边距：行号列用顶部留白与描边框内文本对齐（喵~）
                val editorContentPadding = OutlinedTextFieldDefaults.contentPadding()
                // 编辑器文本样式（与行号同字体同字号），换行/不换行模式共用
                val editorTextStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 左侧行号列：位于 DecorationBox 外（垂直滚动时固定，不随文本横向滚动），
                        // 上下留白与 DecorationBox 的 contentPadding 一致：行号首行与描边框内
                        // 文本首行对齐、底部也留出同款留白；左右不加 padding（行号右对齐自带 8dp 间距，喵~）
                        EditorLineNumbers(
                            textLayout = textLayout,
                            modifier = Modifier.padding(
                                top = editorContentPadding.calculateTopPadding(),
                                bottom = editorContentPadding.calculateBottomPadding(),
                            ),
                        )
                        if (wrapMode) {
                            // 自动换行：BasicTextField 撑满视口宽度，文本正常软换行（喵~）
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .onSizeChanged { editorViewportWidthPx = it.width },
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
                                    textStyle = editorTextStyle,
                                    onTextLayout = { textLayout = it },
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    interactionSource = interactionSource,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 56.dp),
                                ) { innerTextField ->
                                    EditorTextFieldDecorationBox(
                                        value = fieldValue.text,
                                        interactionSource = interactionSource,
                                        contentPadding = editorContentPadding,
                                        onFieldPositioned = { coords ->
                                            // 相对滚动容器的偏移 = 窗口坐标差 + 已滚过的距离
                                            fieldOffsetY = coords.positionInWindow().y.toInt() -
                                                containerY + editScroll.value
                                        },
                                        innerTextField = innerTextField,
                                    )
                                }
                            }
                        } else {
                            // 不换行：horizontalScroll 包在 BasicTextField 外层提供无限宽约束，
                            // CoreTextField 排版为内容宽度 → 不会软换行，且可左右滑动看整行（喵~）。
                            // ⚠️ BasicTextField 不能加 fillMaxWidth：否则又会把排版限制回视口宽度导致换行
                            // ⚠️ 这里**完全不能用** OutlinedTextFieldDefaults.DecorationBox：
                            // 它内部用 OutlinedTextFieldMeasurePolicy 排版，会把内容实际宽度
                            // （超长 HTML 行可达 26 万 px）写进 Constraints，而 Compose 1.7 的
                            // Constraints 上限是 16383px → 崩 "Can't represent a width of ..."（喵~）。
                            // 所以不换行分支退化为「纯 Box 描边框 + contentPadding」，
                            // 视口固定描边框由外层 Box 负责，滚动内容不受边框尺寸限制。
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(OutlinedTextFieldDefaults.shape)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = OutlinedTextFieldDefaults.shape,
                                    )
                                    .onSizeChanged { editorViewportWidthPx = it.width }
                                    .horizontalScroll(editorHScroll),
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
                                    textStyle = editorTextStyle,
                                    onTextLayout = { textLayout = it },
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    interactionSource = interactionSource,
                                    modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                                ) { innerTextField ->
                                    // 复刻 DecorationBox 的内容内边距 + 文本区位置上报，但不用官方
                                    // DecorationBox（它的 MeasurePolicy 扛不住无限宽内容，喵~）
                                    Box(modifier = Modifier.padding(editorContentPadding)) {
                                        Box(
                                            modifier = Modifier.onGloballyPositioned { coords ->
                                                fieldOffsetY = coords.positionInWindow().y.toInt() -
                                                    containerY + editScroll.value
                                            },
                                        ) { innerTextField() }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            else -> {
                when {
                    repository.isMarkdown(currentFile.name) -> {
                        // Markdown：TextView 按内容高度铺开，由外层 Compose verticalScroll 统一滚动，
                        // 避免 TextView 内部 ScrollingMovementMethod 自滚动导致「无惯性、直接刹车」。
                        // previewScroll 提升到顶层：模式切换时保留滚动位置（喵~）
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(previewScroll)
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
                            // 初始滚动比例直接用 htmlScrollFraction（WebView 实时上报）——
                            // 切 tab 回来时由 rememberSaveable 恢复 → 保留阅读位置；
                            // 切模式时 toggleMode 已将 anchorFraction = htmlScrollFraction 后再
                            // 切换 mode → WebView 重建，恢复值与 anchorFraction 等价。
                            initialScrollFraction = htmlScrollFraction,
                            onScrollFractionChanged = { htmlScrollFraction = it },
                        )
                    }
                    else -> {
                        // 普通文本：等宽 + 外层 verticalScroll 滚动，顶部对齐
                        // previewScroll 提升到顶层：模式切换时保留滚动位置（喵~）
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(previewScroll)
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
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
    var singleTextWidthPx by remember { mutableIntStateOf(measured.size.width) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    // 兜底：静态文字真被省略号截断时，强制切换滚动态（防预测量不准导致不滚动，喵~）
    var overflowDetected by remember { mutableStateOf(false) }
    // 两份文字之间的间隔（固定 dp，不依赖字体空格宽度），滚完一份正好第二份接上（喵~）
    val loopGapPx = with(LocalDensity.current) { 32.dp.toPx() }.roundToInt()
    val scrollDistancePx = singleTextWidthPx + loopGapPx
    val shouldScroll = overflowDetected ||
        (containerWidthPx > 0 && singleTextWidthPx > containerWidthPx)

    // 只在需要滚动时启动无限动画：静态展示不空转，且滚动总是从起点开始（喵~）
    val progress = if (shouldScroll) {
        val transition = rememberInfiniteTransition(label = "marqueeTitle")
        transition.animateFloat(
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
        ).value
    } else {
        0f
    }

    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .onSizeChanged { containerWidthPx = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (shouldScroll) {
            Row(
                modifier = Modifier
                    // 关键：解除父容器宽度约束，让双份文字按完整宽度排版，
                    // 否则超出部分不参与布局，向左滚动只会滚出空白（喵~）
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    // 用实际排版宽度校正滚动距离（预测量可能受字体/密度影响，这里最准）
                    .onGloballyPositioned { coords ->
                        val totalWidth = coords.size.width
                        if (totalWidth > 0) {
                            singleTextWidthPx = ((totalWidth - loopGapPx) / 2).coerceAtLeast(0)
                        }
                    }
                    .clickable(onClick = onClick)
                    .offset {
                        IntOffset(x = -(progress * scrollDistancePx).roundToInt(), y = 0)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    style = textStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.width(32.dp))
                Text(
                    text = text,
                    style = textStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        } else {
            Text(
                text = text,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    // 静态文字如果出现省略号/视觉溢出，立刻切滚动态（喵~）
                    if (layout.hasVisualOverflow) overflowDetected = true
                },
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/**
 * 编辑器左侧行号列（喵~）。
 *
 * 显示**逻辑行号**（按 `\n` 分隔的真实文本行），而非视觉行号（软换行不计）。
 * 每个逻辑行号画在其第一个视觉行的顶部，与文本行 baseline 严格对齐。
 * 行号右对齐，列宽按最大行号位数动态计算（至少两位，防止 1-9 行时过窄）。
 * 位于编辑区 [Row] 左侧、DecorationBox 外侧，与文本共享同一垂直滚动容器，
 * 垂直滚动时天然同步；不换行模式下行号列固定在左侧，不随文本横向滚动。
 *
 * ⚠️ **不要用 `getLineForOffset` 把逻辑行起点映射回视觉行**：它的匹配规则是
 * `getLineStart(line) <= offset < getLineEnd(line)`，空行（start == end）无法命中，
 * 会返回下一行——导致空行没有行号、行号重叠在下一行上（喵）。
 * 这里改为「遍历所有视觉行，直接比对 `getLineStart(v)` 是否属于逻辑行起点集合」，
 * 对空行/末尾空行/软换行全部正确（喵~）。
 *
 * 文本内容取自 [TextLayoutResult.layoutInput]——与排版严格同步，
 * 避免输入时序造成「当前文本 vs 旧排版」错位而漏画行号（喵~）。
 */
@Composable
private fun EditorLineNumbers(
    textLayout: TextLayoutResult?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // 与编辑器同字体同字号，颜色弱化（行号不抢正文的视觉焦点，喵~）。
    // 用 remember(color) 固定 style：输入时 color 不变则 style 不变，
    // 避免每次重组都重新 measure 全部行号（大文件输入友好，喵~）
    val lineNumberColor = MaterialTheme.colorScheme.onSurfaceVariant
    val style = remember(lineNumberColor) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = lineNumberColor,
        )
    }
    // 排版时的文本：与 textLayout 同一排版产物，永远同步（喵~）
    val layoutText = textLayout?.layoutInput?.text?.text.orEmpty()

    // 逻辑行起点集合：0 + 每个 `\n` 本身的位置（空行时，getLineStart 返回前面 \n 的 offset）
    // + 每个 `\n` 之后的位置（非空行下一行起点）—— 三者取并集直接去重（喵~）。
    // 注意：Android Layout.getLineStart 对空行返回的是前面换行符的 offset（如 `a\n\nb`
    // 中空行 getLineStart=1, 即第一个 \n 的位置），所以必须把 \n 本身也加入集合，
    // 否则空行匹配不上、没有行号（喵~）。
    val logicalLineStarts = remember(layoutText) {
        val starts = HashSet<Int>(layoutText.length / 2 + 1)
        starts.add(0)
        for (i in 0 until layoutText.length) {
            if (layoutText[i] == '\n') {
                // \n 本身：空行的 getLineStart 返回前面 \n 的 offset（喵~）
                starts.add(i)
                // \n 后还有非 \n 的字符：下一行起点（喵~）
                if (i + 1 < layoutText.length && layoutText[i + 1] != '\n') {
                    starts.add(i + 1)
                }
                // 末尾 \n：不产生新行，自然不处理（喵~）
            }
        }
        starts
    }

    // 逻辑行号 → 该逻辑行首的视觉行（只保留逻辑行首的视觉行，软换行的续行不编号）。
    // 判定「视觉行 v 是不是某逻辑行的第一行」= getLineStart(v) ∈ logicalLineStarts。
    // 这样空行（getLineStart == 换行符后位置）能正确命中自己的行号，而末尾空行
    // （getLineStart == text.length）不在集合里自然不编号（喵~）。
    val lineNumbersByVisualLine = remember(textLayout, logicalLineStarts) {
        val layout = textLayout
        if (layout == null) {
            emptyList<Pair<Int, Int>>()
        } else {
            buildList {
                var number = 0
                for (v in 0 until layout.lineCount) {
                    if (layout.getLineStart(v) in logicalLineStarts) {
                        number++
                        add(v to number)
                    }
                }
            }
        }
    }
    val visibleLineCount = lineNumbersByVisualLine.size

    // 行号列高度 = 最后一个被编号的视觉行底部，而不是整个排版高度：
    // 文本以 \n 结尾时 textLayout 会多一个末尾空行，Canvas 若用整体高度就会多出空行（喵~）
    val contentHeightPx = if (textLayout != null && lineNumbersByVisualLine.isNotEmpty()) {
        val lastVisualLine = lineNumbersByVisualLine.last().first
        textLayout.getLineBottom(lastVisualLine).roundToInt()
    } else {
        0
    }

    // 行号列宽度 = 最大逻辑行号宽度 + 左右留白（等宽数字可预测宽度，喵~）
    val widthPx = remember(visibleLineCount, style, textMeasurer) {
        val digits = visibleLineCount.toString().length.coerceAtLeast(2)
        val sample = textMeasurer.measure(
            text = AnnotatedString("8".repeat(digits)),
            style = style,
            maxLines = 1,
        )
        (sample.size.width.toInt() + 16)
    }

    // 预计算逻辑行号的排版结果：Canvas 内只绘制不测量（大文件/输入频繁时友好，喵~）
    val labels = remember(visibleLineCount, style, textMeasurer) {
        List(visibleLineCount) { i ->
            textMeasurer.measure(
                text = AnnotatedString((i + 1).toString()),
                style = style,
                maxLines = 1,
            )
        }
    }

    Canvas(
        modifier = modifier
            .width(with(density) { widthPx.toDp() })
            .height(with(density) { contentHeightPx.toDp() }),
    ) {
        val layout = textLayout ?: return@Canvas
        val rightPaddingPx = 8.dp.toPx()
        // 遍历「逻辑行首的视觉行」，把行号画在该视觉行顶部（喵~）
        for ((visualLine, number) in lineNumbersByVisualLine) {
            val label = labels.getOrNull(number - 1) ?: break
            // ⚠️ getLineBaseline 返回的是「相对整个布局顶部」的 y（已含 top 偏移），
            // 而 label.getLineBaseline(0) 是「相对 label 顶部」的 y（从 0 开始）。
            // 正确对齐 baseline：drawY = baseline - labelBaseline（同字号时恰好 = top）。
            // 千万不要写成 top + (baseline - labelBaseline)——那会多出一个 top，
            // 行号整体下移 top 距离，顶部的行号全被推到屏幕外（喵~）
            val baseline = layout.getLineBaseline(visualLine)
            val labelBaseline = label.getLineBaseline(0)
            val x = size.width - label.size.width - rightPaddingPx
            val drawY = baseline - labelBaseline
            drawText(
                textLayoutResult = label,
                topLeft = Offset(x, drawY),
            )
        }
    }
}



/**
 * 编辑器描边框（DecorationBox）包装（喵~）。
 *
 * 自动换行模式的 [BasicTextField] 使用官方
 * [OutlinedTextFieldDefaults.DecorationBox] + [OutlinedTextFieldDefaults.Container]
 * 外观。内层通过 [onFieldPositioned] 上报文本区在滚动容器中的偏移，
 * 供垂直光标跟随计算使用。
 *
 * ⚠️ 不换行模式不要用本组件：官方 DecorationBox 的 MeasurePolicy 会把内容实际宽度
 * 写进 Constraints，超长行（>16383px）会崩；那边用纯 Box + 描边框替代（喵~）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTextFieldDecorationBox(
    value: String,
    interactionSource: MutableInteractionSource,
    contentPadding: PaddingValues,
    onFieldPositioned: (LayoutCoordinates) -> Unit,
    innerTextField: @Composable () -> Unit,
) {
    OutlinedTextFieldDefaults.DecorationBox(
        value = value,
        innerTextField = {
            Box(
                Modifier.onGloballyPositioned { coords ->
                    // 文本区顶部相对滚动容器的偏移，由调用方（编辑器）计算为滚动坐标
                    onFieldPositioned(coords)
                },
            ) { innerTextField() }
        },
        enabled = true,
        isError = false,
        singleLine = false,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        colors = OutlinedTextFieldDefaults.colors(),
        contentPadding = contentPadding,
        container = {
            OutlinedTextFieldDefaults.Container(
                enabled = true,
                isError = false,
                interactionSource = interactionSource,
            )
        },
    )
}
