package com.meow.academy.ui.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 编辑器编辑区分片（喵~）。
 *
 * 负责：大 HTML 提示、行号列、垂直/横向光标跟随、自动换行/不换行双实现、描边框。
 * 参数显式化（state + 回调 + 布局瞬态），不读薄壳闭包状态。
 *
 * ⚠️ 三条红线随本文件搬迁保留（禁止"顺手优化"，喵~）：
 * 1. 不换行分支**完全不能用** OutlinedTextFieldDefaults.DecorationBox：其 MeasurePolicy
 *    会把内容实际宽度（超长行可达 26 万 px）写进 Constraints，而 Compose 1.7 的
 *    Constraints 上限是 16383px → 崩（喵~）。
 * 2. 不换行分支的 BasicTextField **不能加 fillMaxWidth**：否则排版被限制回视口宽度导致软换行。
 * 3. 行号列空行用「逻辑行起点集合」而非 `getLineForOffset`（空行 start==end 无法命中，喵~）。
 */
@Composable
internal fun FileEditorTextPane(
    state: EditorUiState,
    onInput: (TextFieldValue) -> Unit,
    controller: FileEditorScrollController,
    editScroll: ScrollState,
    editorHScroll: ScrollState,
    textLayout: TextLayoutResult?,
    viewportHeightPx: Int,
    fieldOffsetY: Int,
    onTextLayout: (TextLayoutResult) -> Unit,
    onViewportHeightChanged: (Int) -> Unit,
    onFieldOffsetChanged: (Int) -> Unit,
) {
    if (state.editBlocked != null) {
        // 大 HTML：可预览但内容不读入内存，编辑区直接提示用终端（喵~）
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = state.editBlocked.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

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
    LaunchedEffect(
        state.fieldValue.selection, textLayout, viewportHeightPx, fieldOffsetY,
        controller.followTick,
    ) {
        if (controller.suppressCursorFollow) return@LaunchedEffect
        // 等一帧：让同帧的 onSizeChanged / onGloballyPositioned 布局值先落定（喵~）
        withFrameNanos { }
        if (controller.suppressCursorFollow) return@LaunchedEffect
        val target = controller.followCursor(
            selection = state.fieldValue.selection,
            layout = textLayout,
            viewportHeightPx = viewportHeightPx,
            fieldOffsetY = fieldOffsetY,
            editScrollValue = editScroll.value,
            textLength = state.fieldValue.text.length,
        ) ?: return@LaunchedEffect
        editScroll.animateScrollTo(target)
    }

    // ── 横向光标跟随（不换行模式，参考 PathEditField 喵~） ──────
    val edgeMarginPx = with(LocalDensity.current) { 16.dp.toPx() }
    var editorViewportWidthPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.fieldValue.selection, textLayout, editorViewportWidthPx, state.wrapMode) {
        if (state.wrapMode) return@LaunchedEffect
        val target = controller.followCursorH(
            selection = state.fieldValue.selection,
            layout = textLayout,
            viewportWidthPx = editorViewportWidthPx,
            hScrollValue = editorHScroll.value,
            hScrollMax = editorHScroll.maxValue,
            edgeMarginPx = edgeMarginPx,
            textLength = state.fieldValue.text.length,
        ) ?: return@LaunchedEffect
        editorHScroll.animateScrollTo(target)
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
            .onSizeChanged { onViewportHeightChanged(it.height) }
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
            if (state.wrapMode) {
                // 自动换行：BasicTextField 撑满视口宽度，文本正常软换行（喵~）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { editorViewportWidthPx = it.width },
                ) {
                    BasicTextField(
                        value = state.fieldValue,
                        onValueChange = { newValue -> onInput(newValue) },
                        textStyle = editorTextStyle,
                        onTextLayout = { onTextLayout(it) },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                    ) { innerTextField ->
                        EditorTextFieldDecorationBox(
                            value = state.fieldValue.text,
                            interactionSource = interactionSource,
                            contentPadding = editorContentPadding,
                            onFieldPositioned = { coords ->
                                // 相对滚动容器的偏移 = 窗口坐标差 + 已滚过的距离
                                onFieldOffsetChanged(
                                    coords.positionInWindow().y.toInt() - containerY + editScroll.value,
                                )
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
                        value = state.fieldValue,
                        onValueChange = { newValue -> onInput(newValue) },
                        textStyle = editorTextStyle,
                        onTextLayout = { onTextLayout(it) },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = interactionSource,
                        modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                    ) { innerTextField ->
                        // 复刻 DecorationBox 的内容内边距 + 文本区位置上报，但不用官方
                        // DecorationBox（它的 MeasurePolicy 扛不住无限宽内容，喵~）
                        Box(modifier = Modifier.padding(editorContentPadding)) {
                            Box(
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    onFieldOffsetChanged(
                                        coords.positionInWindow().y.toInt() - containerY + editScroll.value,
                                    )
                                },
                            ) { innerTextField() }
                        }
                    }
                }
            }
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
fun EditorLineNumbers(
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
fun EditorTextFieldDecorationBox(
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