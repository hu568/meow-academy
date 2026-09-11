package com.meow.academy.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式表格解析单测（纯函数，无 Android 依赖）。
 *
 * 覆盖 [parseStreamingTable]（流式中间态表格解析）与 [isTableDelimiter]（GFM 分隔行判定）。
 * 2026-09-09：原 MarkdownStreamingTest 随半增量拆分器归档后拆分而来——
 * 拆分器用例随 MarkdownStreaming.kt 进 docs/reference/archived/streaming-markdown/，
 * 表格用例留在活测试里（对应代码仍在编译）。
 */
class StreamingTableTest {

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
