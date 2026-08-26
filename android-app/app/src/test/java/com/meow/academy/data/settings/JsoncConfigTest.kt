package com.meow.academy.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSONC 通用管道单测（docs/design-dynamic-config.md §8.1）。
 *
 * 注意：`parseConfigJsonc` 依赖 Android 内置 org.json（JVM 单测为 mock，不在此测）；
 * 这里覆盖纯 Kotlin 的 [stripJsonc] 与 [deepMerge]。
 */
class JsoncConfigTest {

    // ── stripJsonc ─────────────────────────────────────────────

    @Test
    fun `剥行注释`() {
        val input = """
            {
              "a": 1, // 行注释
              "b": 2
            }
        """.trimIndent()
        val out = stripJsonc(input)
        assertEquals(false, out.contains("//"))
        assertEquals(true, out.contains("\"a\""))
        assertEquals(true, out.contains("\"b\""))
    }

    @Test
    fun `剥块注释`() {
        val input = """
            {
              /* 块注释 */
              "a": [1, /* 中间注释 */ 2]
            }
        """.trimIndent()
        val out = stripJsonc(input)
        assertEquals(false, out.contains("/*"))
        assertEquals(false, out.contains("*/"))
    }

    @Test
    fun `字符串内的斜杠与注释不动`() {
        val input = """{ "url": "http://a.com/*x*/", "code": "// not comment" }"""
        val out = stripJsonc(input)
        assertTrue(out.contains("http://a.com/*x*/"))
        assertTrue(out.contains("// not comment"))
    }

    @Test
    fun `转义引号后的斜杠不算字符串结束`() {
        // 字符串里的 \" 不应结束字符串，后面的 // 属于字符串内容
        val input = """{ "s": "a\"//b", "n": 1 }"""
        val out = stripJsonc(input)
        assertTrue(out.contains("a\\\"//b")) // 字面量 a\"//b（含反斜杠）原样保留
        assertTrue(out.contains("\"n\": 1"))
    }

    @Test
    fun `注释替换为换行保持行号`() {
        val input = "{\n  \"a\": 1, // x\n  \"b\": 2\n}"
        val out = stripJsonc(input)
        val lines = out.lines()
        assertEquals(4, lines.size)
        assertTrue(lines[2].contains("\"b\""))
    }

    @Test
    fun `行尾无换行的注释`() {
        assertEquals("{}", stripJsonc("{} // tail").trim())
        assertEquals("{ }", stripJsonc("{ }/* tail").trim())
    }

    // ── deepMerge ──────────────────────────────────────────────

    @Test
    fun `对象递归合并`() {
        val defaults = mapOf(
            "code" to mapOf("a" to 1, "b" to 2),
        )
        val overrides = mapOf(
            "code" to mapOf("b" to 9),
        )
        val merged = deepMerge(defaults, overrides)
        assertEquals(mapOf("code" to mapOf("a" to 1, "b" to 9)), merged)
    }

    @Test
    fun `数组与标量整体替换`() {
        val defaults = mapOf(
            "heading" to mapOf("sizeMultipliers" to listOf(1.6, 1.4)),
            "x" to 1,
        )
        val overrides = mapOf(
            "heading" to mapOf("sizeMultipliers" to listOf(2.0)),
            "x" to 2,
        )
        val merged = deepMerge(defaults, overrides)
        assertEquals(listOf(2.0), merged["heading"]?.let { it as Map<*, *> }?.get("sizeMultipliers"))
        assertEquals(2, merged["x"])
    }

    @Test
    fun `null 视为回退默认`() {
        val defaults = mapOf(
            "quote" to mapOf("color" to "#123456", "widthDp" to 4),
        )
        val overrides = mapOf(
            "quote" to mapOf("color" to null, "widthDp" to 8),
        )
        val merged = deepMerge(defaults, overrides)
        val quote = merged["quote"] as Map<*, *>
        assertEquals("#123456", quote["color"]) // null 回退默认
        assertEquals(8, quote["widthDp"]) // 非 null 覆盖
    }

    @Test
    fun `新键只出现在覆盖中时用覆盖值`() {
        val merged = deepMerge(mapOf("a" to 1), mapOf("b" to 2))
        assertEquals(1, merged["a"])
        assertEquals(2, merged["b"])
    }

    @Test
    fun `不修改入参默认值`() {
        val defaults = LinkedHashMap<String, Any?>()
        defaults["a"] = 1
        val merged = deepMerge(defaults, mapOf("a" to 2))
        assertEquals(1, defaults["a"])
        assertEquals(2, merged["a"])
        assertSame(defaults["a"], 1)
        assertNull(merged["nope"])
    }
}
