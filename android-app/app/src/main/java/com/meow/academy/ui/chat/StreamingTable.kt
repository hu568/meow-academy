package com.meow.academy.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 流式 Markdown 表格（半增量渲染的表格专用通道）。
 *
 * 为什么不用 Markwon 渲染流式表格：
 * - Markwon 的 TableRowSpan 首次测量宽度为 0、靠 draw 后再 invalidate 二次排版，
 *   每 50ms 整表替换一次 Span 就会反复「塌缩 → 弹起」；
 * - 流式时新行到达，Markwon 会整表重新解析渲染，已有行也一起重排。
 *
 * 这里改成 Compose 原生表格：列宽按等分固定，表头与已完成行缓存不动，
 * 只有正在输入的最后一行随 token 变化，从根上消除表格流式跳动。
 * 最终渲染（streaming=false）仍走 Markwon，保留完整表格样式/单元格内 Markdown。
 */

/** 单元格水平对齐 */
enum class StreamingCellAlign { START, CENTER, END }

/** 解析后的流式表格数据：表头 + 对齐 + 已完成数据行 */
data class StreamingTable(
    val header: List<String>,
    val aligns: List<StreamingCellAlign>,
    val rows: List<List<String>>,
)

/**
 * 把当前活动块解析为流式表格；不是表格返回 null。
 *
 * 支持三种流式中间态：
 * - 只有表头行（`| A | B |`）→ 按单行表头表格渲染，避免分隔行到达时从段落跳成表格；
 * - 表头 + 未写完的分隔行（`| A | B |\n|---`）→ 仍按单行表头渲染，未写完的分隔行不显示；
 * - 表头 + 分隔行 + 若干数据行 → 正常表格。
 *
 * 判定为表格的条件：首行含 `|`，且下一非空行是分隔行 / 正在输入的分隔行 / 不存在。
 * 首行含 `|` 但下一行是普通文本时，按普通段落处理（避免误吞含竖线的段落）。
 */
fun parseStreamingTable(markdown: String): StreamingTable? {
    val lines = markdown.split("\n").map { it.removeSuffix("\r") }

    var i = 0
    while (i < lines.size && lines[i].trim().isEmpty()) i++
    if (i >= lines.size) return null

    val headerLine = lines[i]
    if (!headerLine.contains("|")) return null
    val header = splitTableRow(headerLine)
    if (header.isEmpty()) return null

    var j = i + 1
    while (j < lines.size && lines[j].trim().isEmpty()) j++

    if (j < lines.size && !isTableDelimiter(lines[j]) && !isPotentialDelimiterLine(lines[j])) {
        // 首行含 | 但下一行是普通文本 → 不是表格，交给 Markwon 按段落渲染
        return null
    }

    val aligns: List<StreamingCellAlign>
    var bodyStart = j
    if (j < lines.size && isTableDelimiter(lines[j])) {
        aligns = parseDelimiterAligns(lines[j], header.size)
        bodyStart = j + 1
    } else {
        // 分隔行还没写完：先按全左对齐的单行表头渲染
        return StreamingTable(
            header = header,
            aligns = List(header.size) { StreamingCellAlign.START },
            rows = emptyList(),
        )
    }

    val rows = ArrayList<List<String>>()
    for (k in bodyStart until lines.size) {
        val line = lines[k]
        if (line.trim().isEmpty()) break
        if (!line.contains("|")) break
        rows += splitTableRow(line).padTo(header.size)
    }
    return StreamingTable(header = header, aligns = aligns, rows = rows)
}

/** 分隔行单元格对齐：`:---` 左、`---:` 右、`:---:` 中 */
private fun parseDelimiterAligns(line: String, columnCount: Int): List<StreamingCellAlign> {
    val body = line.removeSuffix("\r").trim().removePrefix("|").removeSuffix("|")
    val cells = body.split("|").map { it.trim() }
    return List(columnCount) { index ->
        val cell = cells.getOrElse(index) { "" }
        when {
            cell.startsWith(":") && cell.endsWith(":") -> StreamingCellAlign.CENTER
            cell.endsWith(":") -> StreamingCellAlign.END
            else -> StreamingCellAlign.START
        }
    }
}

/** 拆一行表格：去首尾竖线 → 按 | 切分 → trim；空行/无竖线行返回空列表 */
private fun splitTableRow(line: String): List<String> {
    val t = line.trim()
    if (t.isEmpty() || !t.contains("|")) return emptyList()
    val body = t.removePrefix("|").removeSuffix("|")
    val parts = body.split("|").map { it.trim() }
    // 去掉首尾因可省略竖线产生的空单元格，但保留行中间的空白格
    return parts.filterIndexed { index, cell ->
        !(cell.isEmpty() && (index == 0 || index == parts.size - 1))
    }.ifEmpty { emptyList() }
}

/**
 * 正在输入的分隔行：形如 `|---`、`| ---`、`---`、`|:---:` 等，
 * 全部由 `- : | 空格` 组成（尚未闭合也算）。
 */
private fun isPotentialDelimiterLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    val body = t.removePrefix("|").removeSuffix("|")
    if (body.isBlank()) return false
    return body.split("|").all { cell ->
        val c = cell.trim()
        c.isEmpty() || c.all { it == '-' || it == ':' || it == ' ' }
    }
}

/** 列宽等分的流式表格：表头固定、已完成行缓存、只有最后一行随 token 重排 */
@Composable
fun StreamingTable(
    table: StreamingTable,
    modifier: Modifier = Modifier,
) {
    val columnCount = table.header.size.coerceAtLeast(1)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        key("header") {
            StreamingTableRow(
                cells = table.header,
                aligns = List(columnCount) { StreamingCellAlign.START },
                isHeader = true,
                columnCount = columnCount,
            )
        }
        table.rows.forEachIndexed { index, row ->
            key("row-$index") {
                StreamingTableRow(
                    cells = row.padTo(columnCount),
                    aligns = table.aligns,
                    isHeader = false,
                    columnCount = columnCount,
                )
            }
        }
    }
}

@Composable
private fun StreamingTableRow(
    cells: List<String>,
    aligns: List<StreamingCellAlign>,
    isHeader: Boolean,
    columnCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isHeader) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
            )
            .padding(vertical = 6.dp),
    ) {
        for (index in 0 until columnCount) {
            val cell = cells.getOrElse(index) { "" }
            Text(
                text = stripInlineMarkdown(cell),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                style = if (isHeader) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (isHeader) FontWeight.SemiBold else null,
                textAlign = when (aligns.getOrElse(index) { StreamingCellAlign.START }) {
                    StreamingCellAlign.START -> TextAlign.Start
                    StreamingCellAlign.CENTER -> TextAlign.Center
                    StreamingCellAlign.END -> TextAlign.End
                },
            )
        }
    }
}

/** 单元格内行内 Markdown 降级为纯文本（流式表格只保证稳定，最终渲染会恢复完整格式） */
private fun stripInlineMarkdown(text: String): String =
    text
        .replace(Regex("""!?\[([^\]]*)\]\([^)]*\)"""), "$1")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        .replace(Regex("""\*([^*]+)\*"""), "$1")
        .replace(Regex("""__([^_]+)__"""), "$1")
        .replace(Regex("""_([^_]+)_"""), "$1")
        .replace(Regex("""~~([^~]+)~~"""), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .trim()

/** 补齐/截断到指定列数 */
private fun List<String>.padTo(size: Int): List<String> =
    if (this.size >= size) this.subList(0, size)
    else this + List(size - this.size) { "" }
