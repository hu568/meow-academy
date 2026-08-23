package com.meow.academy.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5.3 表格列宽决策纯函数单测（resolveColumnWidths，无 Android 依赖） */
class MarkdownTableLayoutTest {

    @Test
    fun `空列表原样返回`() {
        assertEquals(emptyList<Int>(), resolveColumnWidths(emptyList(), 320))
    }

    @Test
    fun `视口非法时原样返回`() {
        val natural = listOf(50, 80)
        assertEquals(natural, resolveColumnWidths(natural, 0))
        assertEquals(natural, resolveColumnWidths(natural, -1))
    }

    @Test
    fun `总宽为 0 时原样返回`() {
        val natural = listOf(0, 0)
        assertEquals(natural, resolveColumnWidths(natural, 320))
    }

    @Test
    fun `总宽大于等于视口时保持自然宽`() {
        val natural = listOf(200, 200)
        assertEquals(natural, resolveColumnWidths(natural, 320))
        assertEquals(natural, resolveColumnWidths(natural, 400))
    }

    @Test
    fun `总宽小于视口时按比例放大并撑满`() {
        val natural = listOf(50, 50)
        val result = resolveColumnWidths(natural, 320)
        assertEquals(320, result.sum())
        // 等宽列放大后仍等宽
        assertEquals(listOf(160, 160), result)
    }

    @Test
    fun `按比例放大保持列宽比例`() {
        val natural = listOf(100, 300)
        val result = resolveColumnWidths(natural, 800)
        assertEquals(800, result.sum())
        assertEquals(200, result[0])
        assertEquals(600, result[1])
    }

    @Test
    fun `舍入余数补到前列且总和精确等于视口`() {
        // 3 列等宽 10 → 放大到 100：每列 33，余 1 补给第 1 列
        val natural = listOf(10, 10, 10)
        val result = resolveColumnWidths(natural, 100)
        assertEquals(100, result.sum())
        assertEquals(listOf(34, 33, 33), result)
    }

    @Test
    fun `单列表格占满视口`() {
        assertEquals(listOf(320), resolveColumnWidths(listOf(120), 320))
    }

    @Test
    fun `结果全部非负且保持相对大小顺序`() {
        val natural = listOf(7, 13, 29, 51)
        val result = resolveColumnWidths(natural, 1000)
        assertEquals(1000, result.sum())
        assertTrue(result.all { it >= 0 })
        // 自然宽越大的列，放大后仍应不小于自然宽较小的列
        for (i in 1 until result.size) {
            assertTrue(result[i] >= result[i - 1])
        }
    }
}
