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
 * - 只有表头行（`| A | B |`）→ 按单行表头表格渲染；
 * - 表头 + 未写完的分隔行（`| A | B |\n|---`）→ 仍按单行表头渲染，未写完的分隔行不显示；
 * - 表头 + 分隔行 + 若干数据行 → 正常表格。
 *
 * 判定为表格的条件：首行含 `|`，且下一非空行是分隔行 / 正在输入的分隔行 / 不存在。
 * 首行含 `|` 但下一行是普通文本时，按普通段落处理（避免误吞含竖线的段落）。
 *
 * ⚠️ 「只有表头行」分支**在 App 渲染路径上不可达**：`parseMarkdownBlocks` 的表格守卫要求
 * 「下一非空行存在、且像分隔行」，所以表头刚写完那一帧仍按段落渲染，等分隔行第一个字符
 * 到达才变表格（实测 `| A | B |` → Paragraph，追加 `\n|--` 后才 → Table）。
 * 该分支只对 `parseStreamingTable` 的直接单测可见（见 StreamingTableTest）。
 * 2026-09-11 审计结论：**不放宽守卫** —— 放宽会让「末尾一行含竖线的普通段落」先闪成表格
 * 再退回段落（反向抖动更糟），而现状抖动只有一帧量级（20fps 下 ≤50ms）。
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
 * 判断一行是否为 GFM 表格分隔行：`| --- | :---: | ---: |` 等。
 *
 * **必须含竖线**：GFM 的分隔行按「格」定义，`---`（单格、无竖线）不是分隔行——它是
 * setext 标题下划线或水平分割线。少了这一条，`a | b\n---\n后文` 会把 `---` 吞进表格
 * 并丢掉它（2026-09-11 实测的 A2 bug）。
 *
 * 刻意**不做格数校验**：模型写畸形表（3 列表头 + 1 格分隔行）时，格数校验会让
 * [parseStreamingTable] 走「单行表头」分支、`parseMarkdownBlocks` 又把 `i` 推到表尾，
 * 数据行会被整段吞掉；宽容渲染整表比严格更安全。
 *
 * 2026-09-09 从已归档的 MarkdownStreaming.kt 迁入（原半增量拆分器退役，
 * 但本函数仍被 [StreamingTable] 解析与 [parseMarkdownBlocks] 复用）。
 */
fun isTableDelimiter(line: String): Boolean {
    val trimmed = line.removeSuffix("\r").trim()
    if (trimmed.isEmpty()) return false
    if (!trimmed.contains('|')) return false
    val body = trimmed.removePrefix("|").removeSuffix("|").trim()
    if (body.isEmpty()) return false
    return body.split("|").all { cell ->
        val t = cell.trim()
        t.isNotEmpty() && t.all { it == '-' || it == ':' || it == ' ' }
    }
}

/**
 * 正在输入的分隔行：形如 `|---`、`| ---`、`|:---:` 等，
 * 全部由 `- : | 空格` 组成（尚未闭合也算，允许空格子）。
 *
 * 与 [isTableDelimiter] 同款红线：**必须含竖线**，否则纯 `---` / 单个 `-` 会被当成
 * 「正在输入的分隔行」，让含竖线的段落闪成表格（A2）。
 * 含竖线的半截行（如 `|---|`）仍返回 true，保持流式宽容、不抖动。
 */
internal fun isPotentialDelimiterLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    if (!t.contains('|')) return false
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
