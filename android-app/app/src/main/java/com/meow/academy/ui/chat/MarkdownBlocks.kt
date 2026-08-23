package com.meow.academy.ui.chat

/**
 * 块级 Markdown 元素（M5 块列表渲染的基础）。
 *
 * 由 [parseMarkdownBlocks] 把整篇 Markdown 拆成独立块：
 * - [Paragraph]：普通文本段（标题/列表/引用/行内格式等交给 Markwon 渲染）；
 * - [FencedCode]：围栏代码块（``` / ~~~，language 为 info 串第一个 token）；
 * - [Table]：GFM 表格（复用 [StreamingTable] 数据结构）；
 * - [MathBlock]：`$$…$$` 块公式；
 * - [Mermaid]：` ```mermaid ` 围栏。
 *
 * [closed] 语义：false 表示流式中该块尚未闭合（围栏没有收尾、正在增长），
 * 用于流式 UI 差异（如未闭合 mermaid 显示代码块样式而不是 WebView）。
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class FencedCode(
        val language: String?,
        val code: String,
        val closed: Boolean,
    ) : MdBlock

    data class Table(
        val header: List<String>,
        val aligns: List<StreamingCellAlign>,
        val rows: List<List<String>>,
        val closed: Boolean,
    ) : MdBlock

    data class MathBlock(
        val latex: String,
        val closed: Boolean,
    ) : MdBlock

    data class Mermaid(
        val code: String,
        val closed: Boolean,
    ) : MdBlock

    /** 独立成段的图片：`![alt](src)`，由 [parseStandaloneImage] 识别 */
    data class Image(
        val alt: String,
        val src: String,
        val closed: Boolean,
    ) : MdBlock
}

/**
 * 把整篇 Markdown 拆成块列表（纯函数，无 Android 依赖，可单测）。
 *
 * 扫描规则：
 * - 空行跳过；
 * - ``` / ~~~（含 info 串）与 `$$`（整行纯 $）按围栏块处理，未闭合标记 closed=false；
 * - mermaid 围栏（语言 == "mermaid"，忽略大小写）拆成 [MdBlock.Mermaid]；
 * - 首行含 `|` 且下一非空行是（或正在输入）分隔行 → 表格块，收集到空行/非 `|` 行结束；
 * - 其余连续非空行合并为一个段落块。
 *
 * 行内 `$$x$$`、`$x$` 不会被误判为块围栏（开栏行必须整行只有 $）。
 */
fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.split("\n").map { it.removeSuffix("\r") }
    val blocks = ArrayList<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 空行跳过（块之间以空行分隔，但块内部可能有空行）
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // ① 围栏 / 数学块 / mermaid 开栏检测
        val fenceOpen = detectBlockFenceOpen(trimmed)
        if (fenceOpen != null) {
            val content = ArrayList<String>()
            var closed = false
            var j = i + 1
            while (j < lines.size) {
                if (isBlockFenceClose(lines[j].trim(), fenceOpen.char, fenceOpen.len)) {
                    closed = true
                    j++
                    break
                }
                content += lines[j]
                j++
            }
            when (fenceOpen.kind) {
                FenceKind.MERMAID -> blocks += MdBlock.Mermaid(content.joinToString("\n"), closed)
                FenceKind.MATH -> blocks += MdBlock.MathBlock(content.joinToString("\n"), closed)
                FenceKind.CODE -> blocks += MdBlock.FencedCode(fenceOpen.language, content.joinToString("\n"), closed)
            }
            i = j
            continue
        }

        // ② 表格检测：本行含 |，且下一非空行是分隔行 / 正在输入的分隔行
        if (line.contains("|")) {
            var j = i + 1
            while (j < lines.size && lines[j].trim().isEmpty()) j++
            if (j < lines.size && (isTableDelimiter(lines[j]) || isPotentialDelimiterLine(lines[j]))) {
                var end = j + 1
                while (end < lines.size && lines[end].trim().isNotEmpty() && lines[end].contains("|")) end++
                val table = parseStreamingTable(lines.subList(i, end).joinToString("\n"))
                if (table != null) {
                    blocks += MdBlock.Table(table.header, table.aligns, table.rows, closed = true)
                    i = end
                    continue
                }
            }
        }

        // ③ 普通段落：收集到空行 / 下一个开栏 / 下一个表格表头前
        val textLines = ArrayList<String>()
        textLines += line
        var j = i + 1
        while (j < lines.size) {
            val l = lines[j]
            val t = l.trim()
            if (t.isEmpty()) break
            if (detectBlockFenceOpen(t) != null) break
            // 下一行是分隔行 → 当前行是表头，断块交给表格逻辑
            if (l.contains("|") && j + 1 < lines.size &&
                (isTableDelimiter(lines[j + 1]) || isPotentialDelimiterLine(lines[j + 1]))
            ) {
                break
            }
            textLines += l
            j++
        }
        // 单行独立图片（![alt](src)）拆成图片块，交给 Compose 渲染圆角线框图
        if (textLines.size == 1) {
            val image = parseStandaloneImage(textLines[0])
            if (image != null) {
                blocks += image
                i = j
                continue
            }
        }
        blocks += MdBlock.Paragraph(textLines.joinToString("\n"))
        i = j
    }

    return blocks
}

/** 围栏种类 */
private enum class FenceKind { CODE, MATH, MERMAID }

private data class FenceOpen(
    val kind: FenceKind,
    val char: Char,
    val len: Int,
    val language: String?,
)

/** 开栏检测：``` / ~~~（run >= 3，info=行内剩余）；$$ 块要求整行只有 $（run >= 2） */
private fun detectBlockFenceOpen(trimmed: String): FenceOpen? {
    if (trimmed.isEmpty()) return null
    val first = trimmed[0]
    val run = trimmed.takeWhile { it == first }.length
    return when {
        (first == '`' || first == '~') && run >= 3 -> {
            val info = trimmed.drop(run).trim()
            val language = info.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
            val kind = if (language.equals("mermaid", ignoreCase = true)) FenceKind.MERMAID else FenceKind.CODE
            FenceOpen(kind, first, run, language)
        }
        first == '$' && run >= 2 && trimmed.drop(run).isBlank() ->
            FenceOpen(FenceKind.MATH, first, run, null)
        else -> null
    }
}

/** 闭栏检测：同一字符、围栏要求 run >= 开栏、$$ 要求 run == 开栏，行内剩余只能空白 */
private fun isBlockFenceClose(trimmed: String, fenceChar: Char, fenceLen: Int): Boolean {
    if (trimmed.isEmpty() || trimmed[0] != fenceChar) return false
    val run = trimmed.takeWhile { it == fenceChar }.length
    if (!trimmed.drop(run).isBlank()) return false
    return if (fenceChar == '$') run == fenceLen else run >= fenceLen
}

/** 正在输入的分隔行：形如 `|---`、`| ---`、`---`、`|:---:` 等，全部由 `- : | 空格` 组成 */
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

/**
 * 判断一行是否为独立的 Markdown 水平分割线（thematic break）。
 *
 * CommonMark 允许 `---`、`***`、`___`，也允许带空格分隔（`- - -`、`* * *`）。
 * 这里只要求：去掉空白后剩余字符全部相同，且是 `-` / `*` / `_` 之一、数量 ≥ 3。
 * 渲染层用它决定分割线段落是否应撑满容器宽度。
 */
internal fun isThematicBreakLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    val chars = t.filter { it != ' ' && it != '\t' }
    if (chars.length < 3) return false
    val first = chars.first()
    if (first != '-' && first != '*' && first != '_') return false
    return chars.all { it == first }
}

/** 独立图片行正则：`![alt](src)` / `![alt](<src with spaces>)`，整行必须只有图片 */
private val IMAGE_LINE_REGEX = Regex("""^!\[([^\]]*)\]\(\s*(<[^>]*>|[^)\s]+)\s*\)$""")

/**
 * 识别单行独立图片语法 `![alt](src)`，返回 [MdBlock.Image]；非图片行返回 null。
 *
 * 支持 Markdown 链接目标用 `<…>` 包裹空格/括号的写法（与聊天附件生成的 [markdownLink] 一致），
 * 未闭合（流式中还在输入）的图片语法不识别，继续走 [MdBlock.Paragraph] 显示原文。
 */
internal fun parseStandaloneImage(line: String): MdBlock.Image? {
    val match = IMAGE_LINE_REGEX.matchEntire(line.trim()) ?: return null
    val alt = match.groupValues[1]
    var src = match.groupValues[2].trim()
    if (src.startsWith("<") && src.endsWith(">")) {
        src = src.substring(1, src.length - 1)
    }
    return MdBlock.Image(alt = alt, src = src, closed = true)
}
