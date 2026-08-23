package com.meow.academy.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5.2 块解析器单测（纯函数，无 Android 依赖） */
class MarkdownBlocksTest {

    @Test
    fun `普通段落解析为 Paragraph`() {
        val blocks = parseMarkdownBlocks("hello **world**")
        assertEquals(listOf(MdBlock.Paragraph("hello **world**")), blocks)
    }

    @Test
    fun `空行分隔出多个段落`() {
        val blocks = parseMarkdownBlocks("a\n\nb")
        assertEquals(listOf(MdBlock.Paragraph("a"), MdBlock.Paragraph("b")), blocks)
    }

    @Test
    fun `闭合代码块提取语言与内容`() {
        val blocks = parseMarkdownBlocks("```kotlin\nval x = 1\n```")
        assertEquals(listOf(MdBlock.FencedCode("kotlin", "val x = 1", true)), blocks)
    }

    @Test
    fun `未闭合代码块标记 closed=false`() {
        val blocks = parseMarkdownBlocks("```kotlin\nval x = 1")
        assertEquals(listOf(MdBlock.FencedCode("kotlin", "val x = 1", false)), blocks)
    }

    @Test
    fun `无语言代码块 language 为 null`() {
        val blocks = parseMarkdownBlocks("```\nplain\n```")
        assertEquals(listOf(MdBlock.FencedCode(null, "plain", true)), blocks)
    }

    @Test
    fun `info 串只取第一个 token 作为语言`() {
        val blocks = parseMarkdownBlocks("```python title=hello\nprint(1)\n```")
        assertEquals(listOf(MdBlock.FencedCode("python", "print(1)", true)), blocks)
    }

    @Test
    fun `mermaid 围栏识别`() {
        val blocks = parseMarkdownBlocks("```mermaid\ngraph TD\nA-->B\n```")
        assertEquals(listOf(MdBlock.Mermaid("graph TD\nA-->B", true)), blocks)
    }

    @Test
    fun `mermaid 语言大小写不敏感`() {
        val blocks = parseMarkdownBlocks("```Mermaid\nflowchart LR\na-->b\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Mermaid)
    }

    @Test
    fun `未闭合 mermaid 标记 closed=false`() {
        val blocks = parseMarkdownBlocks("```mermaid\ngraph TD")
        assertEquals(listOf(MdBlock.Mermaid("graph TD", false)), blocks)
    }

    @Test
    fun `闭合数学块`() {
        val blocks = parseMarkdownBlocks("\$\$\nx^2\n\$\$")
        assertEquals(listOf(MdBlock.MathBlock("x^2", true)), blocks)
    }

    @Test
    fun `未闭合数学块标记 closed=false`() {
        val blocks = parseMarkdownBlocks("\$\$\nx^2")
        assertEquals(listOf(MdBlock.MathBlock("x^2", false)), blocks)
    }

    @Test
    fun `行内公式不会被当成块围栏`() {
        val md = "公式 \$x^2\$ 与 \$\$x\$\$ 行内"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(listOf(MdBlock.Paragraph(md)), blocks)
    }

    @Test
    fun `完整表格解析`() {
        val blocks = parseMarkdownBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(
            listOf(
                MdBlock.Table(
                    header = listOf("A", "B"),
                    aligns = listOf(StreamingCellAlign.START, StreamingCellAlign.START),
                    rows = listOf(listOf("1", "2")),
                    closed = true,
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `表头后未写完分隔行解析为单行表头`() {
        val blocks = parseMarkdownBlocks("| A | B |\n|---")
        assertEquals(
            listOf(
                MdBlock.Table(
                    header = listOf("A", "B"),
                    aligns = listOf(StreamingCellAlign.START, StreamingCellAlign.START),
                    rows = emptyList(),
                    closed = true,
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `表格后跟段落`() {
        val blocks = parseMarkdownBlocks("| A |\n| --- |\n| 1 |\n\n后文")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
        assertEquals(MdBlock.Paragraph("后文"), blocks[1])
    }

    @Test
    fun `混合输入按顺序拆块`() {
        val md = """
            标题

            ```kotlin
            val x = 1
            ```

            | A |
            | --- |
            | 1 |

            $$
            y = x^2
            $$

            ```mermaid
            flowchart LR
            a-->b
            ```
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.FencedCode)
        assertTrue(blocks[2] is MdBlock.Table)
        assertTrue(blocks[3] is MdBlock.MathBlock)
        assertTrue(blocks[4] is MdBlock.Mermaid)
    }

    @Test
    fun `CRLF 按 LF 归一化`() {
        val blocks = parseMarkdownBlocks("a\r\n\r\nb")
        assertEquals(listOf(MdBlock.Paragraph("a"), MdBlock.Paragraph("b")), blocks)
    }

    @Test
    fun `代码块内的竖线行不会被拆成表格`() {
        val md = "```\n| a | b |\n| --- | --- |\n```"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.FencedCode)
    }

    @Test
    fun `水平分割线判定覆盖常见写法`() {
        assertTrue(isThematicBreakLine("---"))
        assertTrue(isThematicBreakLine("***"))
        assertTrue(isThematicBreakLine("___"))
        assertTrue(isThematicBreakLine("  ---  "))
        assertTrue(isThematicBreakLine("- - -"))
        assertTrue(isThematicBreakLine("* * *"))
        assertTrue(isThematicBreakLine("_ _ _"))
        assertTrue(isThematicBreakLine("----"))
    }

    @Test
    fun `非分割线不会误判`() {
        assertFalse(isThematicBreakLine("--"))
        assertFalse(isThematicBreakLine("--- text"))
        assertFalse(isThematicBreakLine("**bold**"))
        assertFalse(isThematicBreakLine("- [ ] task"))
        assertFalse(isThematicBreakLine(""))
        assertFalse(isThematicBreakLine("    "))
    }
}
