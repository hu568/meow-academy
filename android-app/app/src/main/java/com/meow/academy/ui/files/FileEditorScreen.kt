package com.meow.academy.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var currentFile by remember { mutableStateOf(file) }

    // ── 语义状态：全部 rememberSaveable 收敛进 EditorUiState（跨 tab 恢复，喵~） ──
    var state by rememberSaveable(stateSaver = EditorUiStateSaver) {
        mutableStateOf(
            EditorUiState(
                mode = if (repository.isHtmlFile(file.name)) EditorMode.PREVIEW else EditorMode.EDIT,
                wrapMode = true,
                fieldValue = TextFieldValue(""),
                undoStack = emptyList(),
                redoStack = emptyList(),
                loadedPath = null,
                isLoading = true,
                previewError = null,
                editBlocked = null,
                htmlContentLoaded = false,
                editScroll = 0,
                previewScroll = 0,
                editorHScroll = 0,
                htmlScrollFraction = 0f,
                anchorFraction = 0f,
            )
        )
    }

    // 三根滚动轴：官方 Saver 保留实例（跨 tab 由 ScrollState.Saver 恢复），值同时镜像进 state（喵~）
    val editScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val previewScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val editorHScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    // 滚动恢复 + 光标同步状态机：瞬态（suppress/pending/follow）从 Compose state 挪进实例（喵~）
    val scrollController = remember { FileEditorScrollController() }

    // 布局瞬态：切回时由 onSizeChanged / onGloballyPositioned / onTextLayout 重新填充
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var fieldOffsetY by remember { mutableIntStateOf(0) }

    // 对话框瞬态：跨 tab 不需要保留
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 切换换行/不换行：布局行数变化，垂直/水平滚动重置回起点（光标由跟随逻辑带回，喵~）。
    // 只在用户操作菜单时调用，避免配置变化恢复状态时误重置编辑位置。
    fun toggleWrapMode(target: Boolean) {
        state = state.copy(wrapMode = target)
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
    LaunchedEffect(state.mode, textLayout, viewportHeightPx, fieldOffsetY) {
        if (state.mode != EditorMode.EDIT || !scrollController.pendingRestore) return@LaunchedEffect
        val plan = scrollController.onRestoreEdit(
            editBlocked = state.editBlocked,
            previewError = state.previewError,
            layout = textLayout,
            viewportHeightPx = viewportHeightPx,
            fieldOffsetY = fieldOffsetY,
            editScrollMax = editScroll.maxValue,
            anchorFraction = state.anchorFraction,
            textLength = state.fieldValue.text.length,
        ) ?: return@LaunchedEffect
        when (plan) {
            RestoreEditPlan.Clear -> Unit // controller 已在 onRestoreEdit 内复位瞬态
            is RestoreEditPlan.Restore -> {
                editScroll.scrollTo(plan.targetScroll)
                val cur = state.fieldValue.selection
                if (cur.start != plan.targetCursor || cur.end != plan.targetCursor) {
                    state = state.copy(
                        fieldValue = state.fieldValue.copy(selection = TextRange(plan.targetCursor)),
                    )
                }
                // 等一帧：让 selection 变化触发的光标跟随协程在 suppressCursorFollow=true 下跳过
                withFrameNanos { }
                // 先解除抑制，再清除待恢复标记：pendingRestore=false 会触发重组把协程取消，
                // 顺序反了的话（pendingRestore=false 在前）下面这行永远执行不到（喵~）
                scrollController.finishRestoreEdit()
            }
        }
    }

    // 切回预览模式（Markdown / 纯文本）：内容布局就绪后按锚点比例恢复；HTML 由 WebView 内部恢复
    LaunchedEffect(state.mode, previewScroll.maxValue, state.anchorFraction) {
        if (state.mode != EditorMode.PREVIEW || repository.isHtmlFile(currentFile.name)) return@LaunchedEffect
        scrollController.onRestorePreview(previewScroll.maxValue, state.anchorFraction)?.let {
            previewScroll.scrollTo(it)
        }
    }

    // 父级（FilesScreen）因重命名回调替换了 file 时，同步本地 currentFile 并触发重新加载
    LaunchedEffect(file.path) {
        if (file.path != currentFile.path) {
            currentFile = file
            state = state.copy(loadedPath = null)
        }
    }

    // 进入时做大小/二进制兜底检查并读取内容。
    // HTML 例外：预览走 WebView 不读内存（不受 1MB 限制），内容切到编辑态再惰性加载（喵~）
    // 重命名场景 loadedPath 已同步为最新路径，这里直接跳过，避免白读一次盘。
    LaunchedEffect(currentFile.path) {
        if (state.loadedPath == currentFile.path) return@LaunchedEffect
        // 先进加载态（旧内容清空提示），再执行 IO（喵~）
        state = state.copy(
            loadedPath = currentFile.path,
            isLoading = true,
            previewError = null,
            editBlocked = null,
            htmlContentLoaded = false,
        )
        val diskFile = File(currentFile.path)
        val isHtml = repository.isHtmlFile(currentFile.name)
        val newPreviewError = when {
            isHtml -> null
            !repository.isTextFile(diskFile) -> "无法预览（二进制或未知格式）"
            diskFile.length() > FileRepository.TEXT_PREVIEW_LIMIT -> "文件过大，请用终端打开"
            else -> null
        }
        val newEditBlocked = if (isHtml && diskFile.length() > FileRepository.TEXT_PREVIEW_LIMIT) {
            "文件过大，请用终端打开"
        } else {
            null
        }
        val newFieldValue = if (newPreviewError == null && !isHtml) {
            TextFieldValue(withContext(Dispatchers.IO) { repository.readText(currentFile.path) })
        } else {
            state.fieldValue
        }
        // 换文件后内容完全不同：滚动位置与跨模式锚点一并重置（重命名不重读不重置，喵~）
        editScroll.scrollTo(0)
        previewScroll.scrollTo(0)
        editorHScroll.scrollTo(0)
        scrollController.suppressCursorFollow = false
        scrollController.pendingRestore = false
        state = state.copy(
            loadedPath = currentFile.path,
            isLoading = false,
            previewError = newPreviewError,
            editBlocked = newEditBlocked,
            fieldValue = newFieldValue,
            undoStack = emptyList(),
            redoStack = emptyList(),
            htmlContentLoaded = false,
            htmlScrollFraction = 0f,
            anchorFraction = 0f,
            editScroll = 0,
            previewScroll = 0,
            editorHScroll = 0,
        )
    }

    // HTML 编辑态惰性加载：进入编辑模式时才读盘，避免预览页/大文件白白读入内存（喵~）
    LaunchedEffect(state.mode) {
        if (state.mode == EditorMode.EDIT &&
            repository.isHtmlFile(currentFile.name) &&
            state.editBlocked == null &&
            state.fieldValue.text.isEmpty()
        ) {
            state = state.copy(
                fieldValue = TextFieldValue(
                    withContext(Dispatchers.IO) { repository.readText(currentFile.path) },
                ),
                htmlContentLoaded = true,
            )
        }
    }

    // 保存：直接 writeText + onSaved；失败 Toast 提示并保留内容
    fun save() {
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { repository.writeText(currentFile.path, state.fieldValue.text) }
            }.isSuccess
            if (ok) {
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                onSaved()
            } else {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun undo() { state = state.undo() }

    fun redo() { state = state.redo() }

    /** 切换编辑/预览模式，并在切换前记录当前视口比例作为恢复锚点（喵~） */
    fun toggleMode() {
        // 同步三根滚动轴当前值到语义状态（供 Saver 保存，喵~）
        state = state.copy(
            editScroll = editScroll.value,
            previewScroll = previewScroll.value,
            editorHScroll = editorHScroll.value,
        )
        state = state.toggleMode(
            isHtml = repository.isHtmlFile(currentFile.name),
            editScrollNow = editScroll.value,
            editScrollMax = editScroll.maxValue,
            previewScrollNow = previewScroll.value,
            previewScrollMax = previewScroll.maxValue,
        )
        // 只有切回编辑模式才需要执行一次「恢复滚动 + 光标同步」（喵~）
        scrollController.onToggleMode(state.mode)
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
                state = state.copy(loadedPath = newFile.path) // 重命名不重读内容，也不清撤销历史（喵~）
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
        FileEditorTopBar(
            onBack = onBack,
            canUndo = state.undoStack.isNotEmpty() && !state.isLoading,
            onUndo = { undo() },
            canRedo = state.redoStack.isNotEmpty() && !state.isLoading,
            onRedo = { redo() },
            canSave = !state.isLoading && state.previewError == null && state.editBlocked == null &&
                (!repository.isHtmlFile(currentFile.name) || state.htmlContentLoaded),
            onSave = { save() },
            canToggleMode = !state.isLoading && state.previewError == null,
            mode = state.mode,
            onToggleMode = { toggleMode() },
            wrapMode = state.wrapMode,
            onWrapModeChange = { toggleWrapMode(it) },
            onRename = { showRenameDialog = true },
            onDelete = { showDeleteConfirm = true },
        )

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
            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.previewError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = state.previewError.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.mode == EditorMode.EDIT -> {
                    FileEditorTextPane(
                        state = state,
                        onInput = { newValue ->
                            if (newValue != state.fieldValue) state = state.onInput(newValue)
                        },
                        controller = scrollController,
                        editScroll = editScroll,
                        editorHScroll = editorHScroll,
                        textLayout = textLayout,
                        viewportHeightPx = viewportHeightPx,
                        fieldOffsetY = fieldOffsetY,
                        onTextLayout = { textLayout = it },
                        onViewportHeightChanged = { viewportHeightPx = it },
                        onFieldOffsetChanged = { fieldOffsetY = it },
                    )
                }
                else -> {
                    FileEditorPreviewPane(
                        state = state,
                        currentFile = currentFile,
                        repository = repository,
                        previewScroll = previewScroll,
                        onHtmlScrollFraction = { state = state.copy(htmlScrollFraction = it) },
                    )
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
        FileDeleteConfirmDialog(
            fileName = currentFile.name,
            onConfirm = { deleteFile() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}