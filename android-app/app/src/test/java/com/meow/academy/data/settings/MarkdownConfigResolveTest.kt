package com.meow.academy.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveMarkdownConfig] 纯函数单测：验证 JSONC 解析 + 深合并后的原始 Map
 * 能正确按主题解析成 [MarkdownConfig]。
 */
class MarkdownConfigResolveTest {

    @Test
    fun `null 原始配置回退内置默认`() {
        val config = resolveMarkdownConfig(null, isDark = false)
        assertEquals(12f, config.formula.blockCornerRadiusDp)
        assertEquals(6f, config.list.bulletWidthDp)
        assertEquals(listOf(1.6f, 1.4f, 1.25f, 1.15f, 1.1f, 1.0f), config.heading.sizeMultipliers)
        assertNull(config.code.blockBackground)
        assertEquals("图片加载失败", config.image.errorText)
    }

    @Test
    fun `浅色主题解析 themed 对象`() {
        val raw = mapOf(
            "formula" to mapOf(
                "blockBackground" to mapOf("light" to "#F2F2F7", "dark" to "#1E1E2E"),
            ),
            "table" to mapOf(
                "headerBackground" to mapOf("light" to "#E8E8ED", "dark" to "#2A2A3A"),
            ),
        )
        val config = resolveMarkdownConfig(raw, isDark = false)
        assertEquals("#F2F2F7", config.formula.blockBackground)
        assertEquals("#E8E8ED", config.table.headerBackground)
    }

    @Test
    fun `深色主题解析 themed 对象`() {
        val raw = mapOf(
            "image" to mapOf(
                "borderColor" to mapOf("light" to "#D1D1D6", "dark" to "#383850"),
            ),
        )
        val config = resolveMarkdownConfig(raw, isDark = true)
        assertEquals("#383850", config.image.borderColor)
    }

    @Test
    fun `themed 缺省一侧回退另一侧`() {
        val raw = mapOf(
            "quote" to mapOf("color" to mapOf("light" to "#FF0000")),
        )
        assertEquals("#FF0000", resolveMarkdownConfig(raw, isDark = false).quote.color)
        assertEquals("#FF0000", resolveMarkdownConfig(raw, isDark = true).quote.color)
    }

    @Test
    fun `padding 与 float 列表解析`() {
        val raw = mapOf(
            "code" to mapOf(
                "inlinePaddingDp" to mapOf("left" to 1.0, "top" to 2.0, "right" to 3.0, "bottom" to 4.0),
            ),
            "heading" to mapOf(
                "sizeMultipliers" to listOf(2.0, 1.5),
            ),
        )
        val config = resolveMarkdownConfig(raw, isDark = false)
        assertEquals(1f, config.code.inlinePaddingDp.left)
        assertEquals(4f, config.code.inlinePaddingDp.bottom)
        assertEquals(listOf(2f, 1.5f), config.heading.sizeMultipliers)
    }

    @Test
    fun `非法颜色回退默认`() {
        val raw = mapOf(
            "link" to mapOf("color" to "not-a-color"),
        )
        val config = resolveMarkdownConfig(raw, isDark = false)
        assertNull(config.link.color)
    }

    @Test
    fun `布尔与整数解析`() {
        val raw = mapOf(
            "formula" to mapOf(
                "blockFitCanvas" to false,
                "blockAlign" to 2,
            ),
            "link" to mapOf("underlined" to false),
        )
        val config = resolveMarkdownConfig(raw, isDark = false)
        assertEquals(false, config.formula.blockFitCanvas)
        assertEquals(2, config.formula.blockAlign)
        assertEquals(false, config.link.underlined)
        assertTrue(config.table.cornerRadiusDp == 12f)
    }
}
