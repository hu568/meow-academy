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
    fun `CRLF 下的标题与围栏不被退化`() {
        val blocks = parseMarkdownBlocks("## 标题\r\n\r\n```js\r\nlet x = 1\r\n```\r\n")
        assertEquals(
            listOf(MdBlock.Paragraph("## 标题"), MdBlock.FencedCode("js", "let x = 1", true)),
            blocks,
        )
    }

    @Test
    fun `表格紧跟段落（无空行）不被段落吞掉`() {
        val blocks = parseMarkdownBlocks("结论如下：\n| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("结论如下："), blocks[0])
        assertEquals(
            MdBlock.Table(
                header = listOf("A", "B"),
                aligns = listOf(StreamingCellAlign.START, StreamingCellAlign.START),
                rows = listOf(listOf("1", "2")),
                closed = true,
            ),
            blocks[1],
        )
    }

    @Test
    fun `代码块后紧跟表格仍能识别`() {
        val blocks = parseMarkdownBlocks("```\ncode\n```\n| A |\n| --- |\n| 1 |")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.FencedCode(null, "code", true), blocks[0])
        assertEquals(
            MdBlock.Table(
                header = listOf("A"),
                aligns = listOf(StreamingCellAlign.START),
                rows = listOf(listOf("1")),
                closed = true,
            ),
            blocks[1],
        )
    }

    @Test
    fun `四反引号围栏不被三反引号提前闭合`() {
        val blocks = parseMarkdownBlocks("````\ncode\n```\nmore\n````")
        assertEquals(listOf(MdBlock.FencedCode(null, "code\n```\nmore", true)), blocks)
        // 未闭合时同样只按 4 反引号收尾（3 个不算闭合）
        assertEquals(
            listOf(MdBlock.FencedCode(null, "code\n```", false)),
            parseMarkdownBlocks("````\ncode\n```"),
        )
    }

    @Test
    fun `围栏内的数学与竖线行不误判`() {
        assertEquals(
            listOf(MdBlock.FencedCode(null, "\$\$\nx", true)),
            parseMarkdownBlocks("```\n\$\$\nx\n```"),
        )
    }

    @Test
    fun `无竖线的分隔行不被吞成表格（A2）`() {
        // `---` 是 setext 下划线 / 分割线，不是 GFM 分隔行；宽容渲染不可丢内容
        val blocks = parseMarkdownBlocks("a | b\n---\n后文")
        assertEquals(listOf(MdBlock.Paragraph("a | b\n---\n后文")), blocks)
    }

    @Test
    fun `只有表头行时块解析仍按段落（A3 现状锁定）`() {
        // parseStreamingTable 的单行表头分支在 App 渲染路径不可达：守卫要求下一非空行存在。
        // 这里把现状钉死，若将来放宽守卫会先撞到这个用例，提醒同步 StreamingTable 文档与真机抖动检查。
        assertEquals(listOf(MdBlock.Paragraph("| A | B |")), parseMarkdownBlocks("| A | B |"))
    }

    @Test
    fun `代码块内的竖线行不会被拆成表格`() {
        val md = "```\n| a | b |\n| --- | --- |\n```"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.FencedCode)
    }

    @Test
    fun `独立图片行解析为 Image 块`() {
        val blocks = parseMarkdownBlocks("![喵喵](file:///tmp/cat.png)")
        assertEquals(listOf(MdBlock.Image("喵喵", "file:///tmp/cat.png", true)), blocks)
    }

    @Test
    fun `图片目标用尖括号包裹时去掉尖括号`() {
        val blocks = parseMarkdownBlocks("![my cat](<file:///tmp/my cat.png>)")
        assertEquals(listOf(MdBlock.Image("my cat", "file:///tmp/my cat.png", true)), blocks)
    }

    @Test
    fun `图片与文字混排仍按段落处理`() {
        val md = "看图 ![cat](cat.png) 很可爱"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(listOf(MdBlock.Paragraph(md)), blocks)
    }

    @Test
    fun `未闭合图片语法不识别为图片块`() {
        val md = "![cat](cat.png"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(listOf(MdBlock.Paragraph(md)), blocks)
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
