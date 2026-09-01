package com.meow.academy.ui.chat

import android.graphics.Color as AndroidColor
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meow.academy.data.settings.MarkdownConfig
import io.noties.markwon.Markwon

/**
 * M5 表格渲染组件（Compose 原生表格，最终渲染通道）。
 *
 * 相比流式阶段的 [StreamingTable] 等分列宽，这里实现完整表格排版：
 * - 圆角背景 + 可配置边框色/圆角半径；
 * - **共享列宽**：所有行（表头 + 数据）放进同一个 [Layout]，
 *   先用 intrinsic 宽度测出每一列的自然宽，再取各列最大值统一排版，保证上下行文本严格对齐；
 * - **占满宽度**：自然总宽 ≤ 容器时按比例放大各列、整表撑满一行；
 *   自然总宽 > 容器时保持自然宽度，外层横向滚动；
 * - **网格线**：表头/斑马纹背景与横竖框线由父级 [Layout] 统一绘制
 *   （复用 borderColor/borderWidthDp），行与行之间连续成框；
 * - 表头与数据行字号统一（bodySmall / 12sp），保证上下行对齐；
 * - 数据单元格使用 [AndroidView] + Markwon 渲染行内 Markdown；
 * - 复制按钮放在**顶部独立工具栏行**（同代码块），不遮挡表格内容，
 *   复制内容为重建的 GFM 表格 Markdown 源。
 *
 * @param table 已解析的流式表格数据（表头 / 对齐 / 数据行）
 * @param modifier 外层修饰符
 * @param isDark 当前是否深色主题（决定表格背景透明度）
 * @param config Markdown 渲染配置（表格外观 / 颜色 / 内边距）
 * @param markwon 用于把单元格 Markdown 文本渲染成 [android.text.Spanned]
 */
@Composable
fun MarkdownTable(
    table: StreamingTable,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    config: MarkdownConfig,
    markwon: Markwon,
) {
    val tableConfig = config.table
    val cornerShape = RoundedCornerShape(tableConfig.cornerRadiusDp.dp)
    val borderColor = parseColor(tableConfig.borderColor)
        ?: MaterialTheme.colorScheme.outlineVariant
    val headerBackground = parseColor(tableConfig.headerBackground)
        ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val altBackground = parseColor(tableConfig.rowAltBackground)
    val textColor = MaterialTheme.colorScheme.onSurface
    val columnCount = table.header.size.coerceAtLeast(1)
    val copyText = remember(table) { rebuildMarkdownSource(table) }
    val gridWidthPx = with(LocalDensity.current) { tableConfig.borderWidthDp.dp.toPx() }
    // 兜底手势注册：LazyColumn 长 item 下半部分的命中区域会截断，交给外层观察器直接驱动滚动
    val scrollState = rememberScrollState()
    DisposableEffect(scrollState) {
        TableScrollRegistry.register(scrollState)
        onDispose { TableScrollRegistry.unregister(scrollState) }
    }

    Column(
        modifier = modifier
            .clip(cornerShape)
            .background(
                if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)
                },
            )
            .border(tableConfig.borderWidthDp.dp, borderColor, cornerShape),
    ) {
        // 顶部工具栏：复制按钮独立成行（右上角），不遮挡表格内容
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            CopyButton(
                text = copyText,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            )
        }

        // 表格主体：BoxWithConstraints 先拿到「视口宽度」，
        // 内部横向滚动只负责内容超宽时的滚动，占满/自然宽由 TableGrid 自己决定。
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val viewportPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
            Row(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .onGloballyPositioned { TableScrollRegistry.updateBounds(scrollState, it.boundsInWindow()) },
            ) {
                TableGrid(
                    table = table,
                    config = config,
                    markwon = markwon,
                    textColor = textColor,
                    headerBackground = headerBackground,
                    altBackground = altBackground,
                    gridColor = borderColor,
                    gridWidthPx = gridWidthPx,
                    viewportPx = viewportPx,
                )
            }
        }
    }
}

/**
 * 表格网格主体：单个自定义 [Layout] 承载表头 + 所有数据行。
 *
 * 子项顺序固定为「表头 N 列 → 数据行 0 的 N 列 → 数据行 1 的 N 列 …」。
 *
 * Compose 约束：同一 measure pass 内每个 [Measurable] 只能 measure 一次，
 * 因此这里不用「先自然宽再固定宽再固定高」的多趟测量，而是：
 * 1. 用 `maxIntrinsicWidth` 读自然列宽（不消耗 measure；AndroidView 也会真实测量内容）；
 *    单列超过视口的按视口截断，让长文本可在视口内折行；
 * 2. [resolveColumnWidths] 决定占满 / 保持自然宽；
 * 3. 对每个 child 只 measure 一次（固定列宽、高度不限）→ 行高取该行最大格高；
 * 4. 表头/斑马纹背景与网格线由父级 [Layout] 的 drawBehind 统一绘制，
 *    矮单元格垂直居中放置，背景与框线依然连续。
 */
@Composable
private fun TableGrid(
    table: StreamingTable,
    config: MarkdownConfig,
    markwon: Markwon,
    textColor: Color,
    headerBackground: Color,
    altBackground: Color?,
    gridColor: Color,
    gridWidthPx: Float,
    viewportPx: Int,
) {
    val tableConfig = config.table
    val columnCount = table.header.size.coerceAtLeast(1)
    val rowCount = table.rows.size + 1 // 表头 + 数据行
    val cellPadding = tableConfig.cellPaddingDp

    // 布局几何：measure 阶段写入，drawBehind 阶段读取（同一帧内先 measure 后 draw，普通 var 即可）
    var columnXs = IntArray(0)
    var rowYs = IntArray(0)

    Layout(
        modifier = Modifier.drawBehind {
            val stroke = gridWidthPx.coerceAtLeast(0.5f)

            // 表头整行背景
            if (rowYs.size > 1) {
                drawRect(
                    color = headerBackground,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, (rowYs[1] - rowYs[0]).toFloat()),
                )
            }

            // 斑马纹：数据行 0-based 奇数（整体第 2、4、6… 行）
            if (altBackground != null) {
                for (r in 2 until rowYs.size - 1 step 2) {
                    drawRect(
                        color = altBackground,
                        topLeft = Offset(0f, rowYs[r].toFloat()),
                        size = Size(size.width, (rowYs[r + 1] - rowYs[r]).toFloat()),
                    )
                }
            }

            // 竖线：非最后一列的右边界
            for (c in 0 until columnXs.size - 2) {
                val x = columnXs[c + 1] - stroke / 2f
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                )
            }

            // 横线：非最后一行的下边界
            for (r in 0 until rowYs.size - 2) {
                val y = rowYs[r + 1] - stroke / 2f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke,
                )
            }
        },
        content = {
            // 表头行
            for (index in 0 until columnCount) {
                val align = table.aligns.alignAt(index)
                TableCell(
                    padding = cellPadding,
                    align = align,
                ) {
                    Text(
                        text = table.header.getOrElse(index) { "" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = align.toTextAlign(),
                    )
                }
            }

            // 数据行：奇偶行交替背景由父级 drawBehind 统一绘制
            table.rows.forEachIndexed { _, row ->
                for (index in 0 until columnCount) {
                    val align = table.aligns.alignAt(index)
                    TableCell(
                        padding = cellPadding,
                        align = align,
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                MarkdownCellTextView(ctx).apply {
                                    textSize = 12f
                                    // ⚠️ 不要 setTextIsSelectable(true)：selectable TextView 会消费所有
                                    // ACTION_DOWN，Compose interop 把它标记为 consumed，导致外层
                                    // horizontalScroll 收不到水平拖拽 → 数据行（表格下半部分）左右滑动
                                    // 穿透到上层抽屉手势（ModalNavigationDrawer / swipeToOpenDashboard）。
                                    // 表头是 Compose Text 不消费所以上半部分能滚；文件管理器无抽屉手势所以正常。
                                    // MarkdownCellTextView 会在「没点到链接」的 ACTION_DOWN 直接返回 false，
                                    // 让水平拖拽交给表格横向滚动；点到链接时仍交给 LinkMovementMethod 正常打开。
                                    movementMethod = LinkMovementMethod.getInstance()
                                }
                            },
                            update = { view ->
                                view.setTextColor(textColor.toArgb())
                                view.textAlignment = align.toViewTextAlignment()
                                view.setText(markwon.toMarkdown(row.getOrElse(index) { "" }))
                            },
                        )
                    }
                }
            }
        },
    ) { measurables, _ ->
        val cellCount = rowCount * columnCount
        require(cellCount == measurables.size) {
            "TableGrid child count mismatch: expected $cellCount, got ${measurables.size}"
        }

        // ① 自然列宽：intrinsic 宽度不消耗 measure；超视口的列按视口截断，长文本可折行
        val natural = IntArray(columnCount)
        for (r in 0 until rowCount) {
            for (c in 0 until columnCount) {
                val w = measurables[r * columnCount + c]
                    .maxIntrinsicWidth(Constraints.Infinity)
                    .coerceAtMost(viewportPx)
                if (w > natural[c]) natural[c] = w
            }
        }

        // ② 列宽决策：占满 or 保持自然宽
        val columnWidths = resolveColumnWidths(natural.toList(), viewportPx)
        val fixed = columnWidths.toIntArray()
        val totalWidth = columnWidths.sum()

        // ③ 唯一一次测量：固定列宽、高度不限
        val placeables = Array(cellCount) { i ->
            val c = i % columnCount
            measurables[i].measure(
                Constraints(minWidth = fixed[c], maxWidth = fixed[c]),
            )
        }

        // ④ 行高 = 该行最大格高
        val rowHeights = IntArray(rowCount) { r ->
            var maxHeight = 0
            for (c in 0 until columnCount) {
                val h = placeables[r * columnCount + c].height
                if (h > maxHeight) maxHeight = h
            }
            maxHeight
        }

        // ⑤ 网格线坐标：列右边界 / 行下边界
        columnXs = IntArray(columnCount + 1).also { xs ->
            var x = 0
            for (c in 0 until columnCount) {
                xs[c] = x
                x += fixed[c]
            }
            xs[columnCount] = x
        }
        rowYs = IntArray(rowCount + 1).also { ys ->
            var y = 0
            for (r in 0 until rowCount) {
                ys[r] = y
                y += rowHeights[r]
            }
            ys[rowCount] = y
        }

        layout(totalWidth, rowYs[rowCount]) {
            var y = 0
            for (r in 0 until rowCount) {
                var x = 0
                for (c in 0 until columnCount) {
                    val placeable = placeables[r * columnCount + c]
                    // 矮单元格在该行内垂直居中
                    val dy = (rowHeights[r] - placeable.height) / 2
                    placeable.place(x, y + dy)
                    x += fixed[c]
                }
                y += rowHeights[r]
            }
        }
    }
}

/**
 * 单个表格单元格：仅负责内边距 + 内容对齐。
 *
 * 背景与网格线由父级 [TableGrid] 统一绘制，单元格本身保持内容自然高度，
 * 因此每个 child 只需 measure 一次（Compose 合法性约束）。
 */
@Composable
private fun TableCell(
    padding: MarkdownConfig.Padding,
    align: StreamingCellAlign,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.tableCellPadding(padding),
        // 水平对齐由 contentAlignment 承担（短文本），长文本换行后再由 TextAlign 补足行内对齐
        contentAlignment = when (align) {
            StreamingCellAlign.START -> Alignment.CenterStart
            StreamingCellAlign.CENTER -> Alignment.Center
            StreamingCellAlign.END -> Alignment.CenterEnd
        },
    ) {
        content()
    }
}

/**
 * 由自然列宽决定最终列宽（纯函数，可单测）。
 *
 * - 空列表 / 视口非法 / 总宽为 0 → 原样返回；
 * - 总宽 ≥ 视口 → 原样返回（外层横向滚动）；
 * - 总宽 < 视口 → 按自然宽比例放大，并把舍入余数逐像素补给前列，
 *   使 `结果总和 == viewport`（表格自动左右占满）。
 */
internal fun resolveColumnWidths(natural: List<Int>, viewport: Int): List<Int> {
    if (natural.isEmpty() || viewport <= 0) return natural
    val total = natural.sum()
    if (total <= 0 || total >= viewport) return natural

    val scaled = natural.map { (it.toLong() * viewport / total).toInt() }
    val result = scaled.toMutableList()
    var remainder = viewport - scaled.sum()
    var index = 0
    while (remainder > 0) {
        result[index % result.size] += 1
        remainder--
        index++
    }
    return result
}

/** 单元格内边距：来自配置的 TableConfig.cellPaddingDp */
private fun Modifier.tableCellPadding(padding: MarkdownConfig.Padding): Modifier =
    padding(
        start = padding.left.dp,
        top = padding.top.dp,
        end = padding.right.dp,
        bottom = padding.bottom.dp,
    )

/** 取指定列的对齐方式；缺失列按左对齐处理 */
private fun List<StreamingCellAlign>.alignAt(index: Int): StreamingCellAlign =
    getOrElse(index) { StreamingCellAlign.START }

/** Compose Text 对齐 */
private fun StreamingCellAlign.toTextAlign(): TextAlign = when (this) {
    StreamingCellAlign.START -> TextAlign.Start
    StreamingCellAlign.CENTER -> TextAlign.Center
    StreamingCellAlign.END -> TextAlign.End
}

/** TextView 对齐（View 体系常量） */
private fun StreamingCellAlign.toViewTextAlignment(): Int = when (this) {
    StreamingCellAlign.START -> View.TEXT_ALIGNMENT_TEXT_START
    StreamingCellAlign.CENTER -> View.TEXT_ALIGNMENT_CENTER
    StreamingCellAlign.END -> View.TEXT_ALIGNMENT_TEXT_END
}

/** 重建 GFM 表格 Markdown 源，供右上角复制按钮使用 */
private fun rebuildMarkdownSource(table: StreamingTable): String {
    val columnCount = table.header.size.coerceAtLeast(1)

    // 表头行：| A | B |
    val headerLine = table.header
        .take(columnCount)
        .joinToString(" | ", prefix = "| ", postfix = " |")

    // 分隔行：按对齐生成 | --- | :---: | ---: |
    val delimiterLine = List(columnCount) { index ->
        when (table.aligns.alignAt(index)) {
            StreamingCellAlign.START -> "---"
            StreamingCellAlign.CENTER -> ":---:"
            StreamingCellAlign.END -> "---:"
        }
    }.joinToString(" | ", prefix = "| ", postfix = " |")

    // 数据行：| 1 | 2 |；列不足补空串，超出部分不参与重建
    val bodyLines = table.rows.map { row ->
        (0 until columnCount)
            .joinToString(" | ") { index -> row.getOrElse(index) { "" } }
            .let { "| $it |" }
    }

    return buildString {
        append(headerLine)
        append('\n')
        append(delimiterLine)
        if (bodyLines.isNotEmpty()) {
            append('\n')
            append(bodyLines.joinToString("\n"))
        }
    }
}

/**
 * 解析配置颜色字符串。
 *
 * 支持 `#RRGGBB` 与 `#AARRGGBB`；解析失败或 null 返回 null。
 * 注意 `android.graphics.Color.parseColor("#RRGGBB")` 返回的 Int 已带 FF alpha，
 * 直接交给 Compose 的 [Color] 构造器即可得到正确的不透明颜色。
 */
private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
}
