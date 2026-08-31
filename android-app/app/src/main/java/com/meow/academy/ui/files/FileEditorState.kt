package com.meow.academy.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.roundToInt

/** 编辑页两种视图模式（喵~） */
internal enum class EditorMode { EDIT, PREVIEW }

/** EditorMode 的 Saver：存枚举名（String），恢复时 valueOf */
internal val EditorModeSaver = Saver<EditorMode, String>(
    save = { it.name },
    restore = { runCatching { EditorMode.valueOf(it) }.getOrNull() },
)

/** List<TextFieldValue> 的 Saver：组合 TextFieldValue.Saver 用于 undo/redo 栈 */
internal val TextFieldValueListSaver: Saver<List<TextFieldValue>, Any> = listSaver(
    save = { list: List<TextFieldValue> ->
        with(TextFieldValue.Saver) { list.map { save(it)!! } }
    },
    restore = { list: List<Any> ->
        with(TextFieldValue.Saver) { list.map { restore(it)!! } }
    },
)

/** 撤销/恢复历史最大步数 */
internal const val MAX_UNDO_HISTORY = 100

/**
 * 可跨 tab 保存的编辑器语义状态（全部 rememberSaveable 字段收敛于此，喵~）。
 *
 * 三根滚动轴的值（editScroll / previewScroll / editorHScroll）同时镜像进本类，
 * 供 Saver 跨 tab 保留；运行时 ScrollState 实例由薄壳维护（双方 via 官方 Saver 各自恢复一致）。
 * 布局瞬态（textLayout/viewportHeightPx/fieldOffsetY）不入本类——它们由 onSizeChanged 等重新填充。
 */
internal data class EditorUiState(
    val mode: EditorMode,
    val wrapMode: Boolean,
    val fieldValue: TextFieldValue,
    val undoStack: List<TextFieldValue>,
    val redoStack: List<TextFieldValue>,
    val loadedPath: String?,
    val isLoading: Boolean,
    val previewError: String?,
    val editBlocked: String?,
    val htmlContentLoaded: Boolean,
    val editScroll: Int,
    val previewScroll: Int,
    val editorHScroll: Int,
    val htmlScrollFraction: Float,
    val anchorFraction: Float,
) {
    /** 记录输入：旧值压栈（限长 MAX_UNDO_HISTORY），清空 redo */
    fun onInput(newValue: TextFieldValue): EditorUiState =
        if (newValue == fieldValue) this
        else copy(
            fieldValue = newValue,
            undoStack = (undoStack + fieldValue).takeLast(MAX_UNDO_HISTORY),
            redoStack = emptyList(),
        )

    /** 撤销：当前值进 redo，弹出 undo 栈顶 */
    fun undo(): EditorUiState {
        val last = undoStack.lastOrNull() ?: return this
        return copy(
            fieldValue = last,
            undoStack = undoStack.dropLast(1),
            redoStack = redoStack + fieldValue,
        )
    }

    /** 恢复：当前值进 undo，弹出 redo 栈顶 */
    fun redo(): EditorUiState {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            fieldValue = next,
            undoStack = undoStack + fieldValue,
            redoStack = redoStack.dropLast(1),
        )
    }

    /** 切换编辑/预览，并在切换前记录当前视口比例作为恢复锚点（喵~） */
    fun toggleMode(
        isHtml: Boolean,
        editScrollNow: Int,
        editScrollMax: Int,
        previewScrollNow: Int,
        previewScrollMax: Int,
    ): EditorUiState {
        val anchor = when (mode) {
            EditorMode.EDIT ->
                if (editScrollMax > 0) editScrollNow / editScrollMax.toFloat() else 0f
            EditorMode.PREVIEW ->
                if (isHtml) htmlScrollFraction
                else if (previewScrollMax > 0) previewScrollNow / previewScrollMax.toFloat() else 0f
        }
        val nextMode = if (mode == EditorMode.EDIT) EditorMode.PREVIEW else EditorMode.EDIT
        return copy(anchorFraction = anchor, mode = nextMode)
    }
}

/** EditorUiState 的自定义 Saver：字段一一对应（与 §0.2 状态清单一致，喵~） */
internal val EditorUiStateSaver: Saver<EditorUiState, Any> = listSaver(
    save = { s: EditorUiState ->
        listOf(
            s.mode.name,
            s.wrapMode,
            with(TextFieldValue.Saver) { save(s.fieldValue)!! },
            with(TextFieldValue.Saver) { s.undoStack.map { save(it)!! } },
            with(TextFieldValue.Saver) { s.redoStack.map { save(it)!! } },
            s.loadedPath,
            s.isLoading,
            s.previewError,
            s.editBlocked,
            s.htmlContentLoaded,
            s.editScroll,
            s.previewScroll,
            s.editorHScroll,
            s.htmlScrollFraction,
            s.anchorFraction,
        )
    },
    restore = { list: List<Any?> ->
        @Suppress("UNCHECKED_CAST")
        EditorUiState(
            mode = runCatching { EditorMode.valueOf(list[0] as String) }.getOrDefault(EditorMode.EDIT),
            wrapMode = list[1] as? Boolean ?: true,
            fieldValue = with(TextFieldValue.Saver) { restore(list[2]!!) } ?: TextFieldValue(""),
            undoStack = with(TextFieldValue.Saver) { (list[3] as List<*>).map { restore(it!!)!! } },
            redoStack = with(TextFieldValue.Saver) { (list[4] as List<*>).map { restore(it!!)!! } },
            loadedPath = list[5] as? String,
            isLoading = list[6] as? Boolean ?: false,
            previewError = list[7] as? String,
            editBlocked = list[8] as? String,
            htmlContentLoaded = list[9] as? Boolean ?: false,
            editScroll = (list[10] as? Number)?.toInt() ?: 0,
            previewScroll = (list[11] as? Number)?.toInt() ?: 0,
            editorHScroll = (list[12] as? Number)?.toInt() ?: 0,
            htmlScrollFraction = (list[13] as? Number)?.toFloat() ?: 0f,
            anchorFraction = (list[14] as? Number)?.toFloat() ?: 0f,
        )
    },
)

/** 切回编辑滚动恢复的返回值（喵~） */
sealed class RestoreEditPlan {
    /** 无需恢复（编辑区不渲染 / 内容不可滚动），直接放行 */
    data object Clear : RestoreEditPlan()

    /** 需要恢复：目标滚动值 + 视口顶部对应光标 offset */
    data class Restore(val targetScroll: Int, val targetCursor: Int) : RestoreEditPlan()
}

/**
 * 编辑/预览切换的滚动恢复 + 光标同步状态机（纯逻辑，不直接操作 UI，喵~）。
 *
 * 输入：当前瞬态快照 + 布局测量值；输出：目标滚动值/目标光标 offset。
 * 语义原样搬移自 FileEditorScreen 原 236-271/583-628 行——时序是被真机调出来的，
 * 禁止"顺手优化"（喵~）。
 *
 * 瞬态变量（suppressCursorFollow / pendingRestore / followTick）从 Compose state
 * 挪进本实例，但 followTick 为快照状态（mutableIntStateOf）以保持 LaunchedEffect 可观察性。
 */
internal class FileEditorScrollController {
    /** 抑制光标自动跟随的 flag（恢复初始 false 即可，喵~） */
    var suppressCursorFollow = false

    /** 本次切回编辑模式后是否还有「恢复滚动」待执行 */
    var pendingRestore = false

    /**
     * 恢复流程结束（释放 suppressCursorFollow）后 +1，强制光标跟随协程补跑一次（喵~）。
     * 快照状态：LaunchedEffect 以 followTick 为 key 时变更可触发重跑。
     */
    var followTick by mutableIntStateOf(0)

    /** 切回编辑模式（toggleMode 时调用）：记录抑制 + 待恢复标记 */
    fun onToggleMode(nextMode: EditorMode) {
        suppressCursorFollow = true
        // 只有切回编辑模式才需要执行一次「恢复滚动 + 光标同步」（喵~）
        pendingRestore = nextMode == EditorMode.EDIT
    }

    /**
     * 切回编辑：等布局就绪后按 anchorFraction 恢复滚动，并把光标同步到恢复位置。
     * 条件未满足（布局未就绪）返回 null，等下一次布局事件再试（喵~）。
     *
     * 返回 Clear 时 controller 已自动复位瞬态；返回 Restore 时调用方需执行
     * scrollTo + 设 selection + withFrameNanos + finishRestoreEdit()。
     */
    fun onRestoreEdit(
        editBlocked: String?,
        previewError: String?,
        layout: TextLayoutResult?,
        viewportHeightPx: Int,
        fieldOffsetY: Int,
        editScrollMax: Int,
        anchorFraction: Float,
        textLength: Int,
    ): RestoreEditPlan? {
        // 编辑区不渲染（大 HTML 提示 / 不可预览）时无需恢复，直接放行（喵~）
        if (editBlocked != null || previewError != null) {
            pendingRestore = false
            suppressCursorFollow = false
            followTick++
            return RestoreEditPlan.Clear
        }
        val l = layout ?: return null
        if (viewportHeightPx <= 0) return null
        // 文本区尚未被 onGloballyPositioned 定位（fieldOffsetY 仍为 0）时等下一轮触发，避免光标算错（喵~）
        if (fieldOffsetY <= 0) return null
        if (editScrollMax <= 0) {
            pendingRestore = false
            suppressCursorFollow = false
            followTick++
            return RestoreEditPlan.Clear
        }
        suppressCursorFollow = true
        val targetScroll = (anchorFraction * editScrollMax).roundToInt()
        // 视口顶部滚动坐标换算成文本布局内 y，取该行起始 offset 作为新光标位置（喵~）
        val topLine = l.getLineForVerticalPosition((targetScroll - fieldOffsetY).coerceAtLeast(0).toFloat())
        val topOffset = l.getLineStart(topLine).coerceIn(0, textLength)
        return RestoreEditPlan.Restore(targetScroll, topOffset)
    }

    /**
     * 恢复流程收尾：等一帧后调用。先解除抑制，再清除待恢复标记——
     * pendingRestore=false 会触发重组把协程取消，顺序反了的话下面这行永远执行不到（喵~）
     */
    fun finishRestoreEdit() {
        suppressCursorFollow = false
        // 释放抑制后立刻 +1：把「selection 变化时因抑制而跳过」的光标跟随补跑一次（喵~）
        followTick++
        pendingRestore = false
    }

    /** 切回预览（Markdown / 纯文本）：按锚点比例恢复滚动；HTML 由 WebView 内部恢复 */
    fun onRestorePreview(previewScrollMax: Int, anchorFraction: Float): Int? =
        if (previewScrollMax > 0) (anchorFraction * previewScrollMax).roundToInt() else null

    /** 光标跟随（垂直）：光标或视口变化时把光标行滚进视口上半部，返回目标滚动值（null=无需滚动） */
    fun followCursor(
        selection: TextRange,
        layout: TextLayoutResult?,
        viewportHeightPx: Int,
        fieldOffsetY: Int,
        editScrollValue: Int,
        textLength: Int,
    ): Int? {
        if (suppressCursorFollow) return null
        val l = layout ?: return null
        if (viewportHeightPx <= 0) return null
        val offset = selection.end.coerceIn(0, textLength)
        val line = l.getLineForOffset(offset)
        val lineTop = fieldOffsetY + l.getLineTop(line).toInt()
        val lineBottom = fieldOffsetY + l.getLineBottom(line).toInt()
        val viewStart = editScrollValue
        val comfortEnd = viewStart + viewportHeightPx / 2
        // 已完整落在视口上半部则不打扰；否则滚到视口约 1/4 高度处
        if (lineTop < viewStart || lineBottom > comfortEnd) {
            return (lineTop - viewportHeightPx / 4).coerceAtLeast(0)
        }
        return null
    }

    /** 光标跟随（横向，不换行模式）：返回目标横向滚动值（null=无需滚动） */
    fun followCursorH(
        selection: TextRange,
        layout: TextLayoutResult?,
        viewportWidthPx: Int,
        hScrollValue: Int,
        hScrollMax: Int,
        edgeMarginPx: Float,
        textLength: Int,
    ): Int? {
        val l = layout ?: return null
        if (viewportWidthPx <= 0 || textLength <= 0) return null
        val startX = l.getHorizontalPosition(selection.min.coerceIn(0, textLength), true).coerceAtLeast(0f)
        val endX = l.getHorizontalPosition(selection.max.coerceIn(0, textLength), true).coerceAtLeast(0f)
        val viewStart = hScrollValue.toFloat()
        val viewEnd = viewStart + viewportWidthPx
        val target = when {
            startX < viewStart -> startX - edgeMarginPx
            endX > viewEnd - edgeMarginPx -> endX - viewportWidthPx + edgeMarginPx
            else -> return null
        }
        return target.roundToInt().coerceIn(0, hScrollMax)
    }
}