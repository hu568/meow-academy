package com.meow.academy.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 流式 Markdown 块拆分器单测（纯函数，无 Android 依赖） */
class MarkdownStreamingTest {

    @Test
    fun `单段落未终止时全部为活动块`() {
        val blocks = splitStreamingBlocks("hello **wor")
        assertEquals(emptyList<String>(), blocks.stable)
        assertEquals("hello **wor", blocks.active)
    }

    @Test
    fun `空行终止前一块且活动块为空`() {
        val blocks = splitStreamingBlocks("first paragraph\n\n")
        assertEquals(listOf("first paragraph"), blocks.stable)
        assertEquals("", blocks.active)
    }

    @Test
    fun `前块稳定后新行成为活动块`() {
        val blocks = splitStreamingBlocks("first\n\nsecond")
        assertEquals(listOf("first"), blocks.stable)
        assertEquals("second", blocks.active)
    }

    @Test
    fun `未闭合围栏全部留在活动块`() {
        val md = "```kotlin\nval x = 1"
        val blocks = splitStreamingBlocks(md)
        assertEquals(emptyList<String>(), blocks.stable)
        assertEquals(md, blocks.active)
    }

    @Test
    fun `闭合围栏进稳定块后文成为活动块`() {
        val blocks = splitStreamingBlocks("```kotlin\nval x = 1\n```\n\ntext")
        assertEquals(listOf("```kotlin\nval x = 1\n```"), blocks.stable)
        assertEquals("text", blocks.active)
    }

    @Test
    fun `表头加分隔行即成为活动表格块`() {
        val md = "| A | B |\n| --- | --- |"
        val blocks = splitStreamingBlocks(md)
        assertEquals(emptyList<String>(), blocks.stable)
        assertEquals(md, blocks.active)
    }

    @Test
    fun `表格逐行追加且遇到非竖线行结束`() {
        val blocks = splitStreamingBlocks("para\n| A | B |\n| --- | --- |\n| 1 | 2 |\nafter")
        assertEquals(listOf("para", "| A | B |\n| --- | --- |\n| 1 | 2 |"), blocks.stable)
        assertEquals("after", blocks.active)
    }

    @Test
    fun `闭合美元块进稳定块`() {
        val blocks = splitStreamingBlocks("$$\nx^2\n$$")
        assertEquals(listOf("$$\nx^2\n$$"), blocks.stable)
        assertEquals("", blocks.active)
    }

    @Test
    fun `未闭合美元块留在活动块`() {
        val md = "$$\nx^2"
        val blocks = splitStreamingBlocks(md)
        assertEquals(emptyList<String>(), blocks.stable)
        assertEquals(md, blocks.active)
    }

    @Test
    fun `行内公式不会被当成块围栏`() {
        val md = "\$\$x^2\$\$"
        val blocks = splitStreamingBlocks(md)
        assertEquals(emptyList<String>(), blocks.stable)
        assertEquals(md, blocks.active)
    }

    @Test
    fun `美元开栏后非纯美元行不闭合围栏`() {
        val md = "\$\$\nx^2\n\$\$ 与行内 \$\$x^2\$\$ 同行"
        // 开栏行必须整行只有 $，所以 "$$ 与行内..." 既不闭合也不开新栏
        val blocks = splitStreamingBlocks(md)
        assertEquals(emptyList<String>(), blocks.stable)
        assertEquals(md, blocks.active)
    }

    @Test
    fun `CRLF 按 LF 归一化`() {
        val blocks = splitStreamingBlocks("a\r\n\r\nb")
        assertEquals(listOf("a"), blocks.stable)
        assertEquals("b", blocks.active)
    }

    @Test
    fun `分隔行判断覆盖常见对齐写法`() {
        assertEquals(true, isTableDelimiter("| --- | --- |"))
        assertEquals(true, isTableDelimiter("--- | :---: | ---:"))
        assertEquals(false, isTableDelimiter("| a | b |"))
        assertEquals(false, isTableDelimiter(""))
    }

    @Test
    fun `只有表头行也解析为单行表头表格`() {
        val table = parseStreamingTable("| A | B |")
        assertEquals(listOf("A", "B"), table?.header)
        assertEquals(emptyList<List<String>>(), table?.rows)
    }

    @Test
    fun `表头加未写完分隔行仍为单行表头表格`() {
        val table = parseStreamingTable("| A | B |\n|---")
        assertEquals(listOf("A", "B"), table?.header)
        assertEquals(emptyList<List<String>>(), table?.rows)
    }

    @Test
    fun `表头加完整分隔行解析对齐`() {
        val table = parseStreamingTable("| A | B |\n|:--- | ---:|")
        assertEquals(listOf(StreamingCellAlign.START, StreamingCellAlign.END), table?.aligns)
        assertEquals(emptyList<List<String>>(), table?.rows)
    }

    @Test
    fun `表格逐行追加时已有行保持稳定`() {
        val table = parseStreamingTable("| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4")
        assertEquals(listOf("A", "B"), table?.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), table?.rows)
    }

    @Test
    fun `含竖线的普通段落不误判为表格`() {
        assertEquals(null, parseStreamingTable("速度 | 5 m/s\n这是第二行"))
    }

    @Test
    fun `空文本不解析为表格`() {
        assertEquals(null, parseStreamingTable(""))
    }
}
