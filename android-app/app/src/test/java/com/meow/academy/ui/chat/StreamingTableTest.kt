package com.meow.academy.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式表格解析单测（纯函数，无 Android 依赖）。
 *
 * 覆盖 [parseStreamingTable]（流式中间态表格解析）与 [isTableDelimiter]（GFM 分隔行判定）。
 * 2026-09-09：原 MarkdownStreamingTest 随半增量拆分器归档后拆分而来——
 * 拆分器用例随 MarkdownStreaming.kt 进 docs/reference/archived/streaming-markdown/，
 * 表格用例留在活测试里（对应代码仍在编译）。
 *
 * ⚠️「只有表头行」用例只覆盖本函数的直接调用；App 渲染路径（[parseMarkdownBlocks]）有守卫，
 * 表头单独一帧仍按段落渲染（现状由 MarkdownBlocksTest 锁定，勿据此以为表格已经提前成型）。
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
    fun `无竖线的行不算分隔行（A2）`() {
        assertFalse(isTableDelimiter("---"))
        assertFalse(isTableDelimiter("-"))
        assertFalse(isTableDelimiter(":-:"))
        assertFalse(isTableDelimiter("  ---  "))
        // 含竖线的半截 / 单格写法仍是分隔行（保持流式宽容，不抖动）
        assertTrue(isTableDelimiter("|---|"))
        assertTrue(isTableDelimiter("| ---"))
    }

    @Test
    fun `无竖线的行不算正在输入的分隔行（A2 宽容性）`() {
        assertFalse(isPotentialDelimiterLine("---"))
        assertFalse(isPotentialDelimiterLine("-"))
        assertFalse(isPotentialDelimiterLine(""))
        // 含竖线：即便只有一格也按「分隔行在路上」处理，3 列表头 + |---| 不抖动
        assertTrue(isPotentialDelimiterLine("|---|"))
        assertTrue(isPotentialDelimiterLine("|:--"))
        assertEquals(listOf("A", "B", "C"), parseStreamingTable("| A | B | C |\n|---|")?.header)
        // 段落 + 裸 --- ：不是表格，内容留给段落渲染
        assertNull(parseStreamingTable("a | b\n---\n后文"))
    }

    @Test
    fun `糙行表格补列与截列到表头列数（A4）`() {
        val table = parseStreamingTable("| A | B |\n| --- | --- |\n| 1 |\n| 1 | 2 | 3 |")
        assertEquals(listOf("A", "B"), table?.header)
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), table?.rows)
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
