package com.meow.academy.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 单 `$…$` 行内公式匹配纯函数单测 */
class DollarMathTest {

    @Test
    fun `匹配基本行内公式`() {
        assertEquals("x^2", matchDollarMath("\$x^2\$", 0))
    }

    @Test
    fun `匹配段中行内公式`() {
        assertEquals("x", matchDollarMath("a \$x\$ b", 2))
    }

    @Test
    fun `双美元不匹配`() {
        assertNull(matchDollarMath("\$\$x^2\$\$", 0))
        assertNull(matchDollarMath("\$\$x^2\$\$", 1))
    }

    @Test
    fun `货币类文本不匹配`() {
        // 闭 $ 前是空格，不是公式
        assertNull(matchDollarMath("\$5 to \$10", 0))
    }

    @Test
    fun `开美元后空白不匹配`() {
        assertNull(matchDollarMath("\$ x\$", 0))
    }

    @Test
    fun `未闭合不匹配`() {
        assertNull(matchDollarMath("\$x", 0))
    }

    @Test
    fun `闭美元后紧跟美元不匹配`() {
        assertNull(matchDollarMath("\$x\$\$", 0))
    }
}
