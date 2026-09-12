package com.meow.academy.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `assistant/message` 权威内容解析 / 合并单测（纯函数，无 Android 依赖）。
 *
 * 背景（0.1.5 基线回归）：上游 agent loop 不再逐 delta 发 `assistant/chunk`，
 * 只在 step 结束时发一条带完整 content 的 `assistant/message`。App 此前只认 chunk，
 * 于是每条助手回复都落成空消息（渲染「（空回复）」）——聊天页无法对话。
 * 这里锁住「唯一权威来源」的解析与幂等合并语义。
 */
class AssistantMessageSegmentsTest {

    private fun blocks(raw: String): JsonArray = Json.parseToJsonElement(raw) as JsonArray

    @Test
    fun `content 块按顺序映射为分段`() {
        val segs = assistantMessageSegments(
            blocks(
                """
                [
                  {"type":"reasoning","text":"让我想想"},
                  {"type":"text","text":"答案是 42"},
                  {"type":"tool-call","id":"c1","name":"bash","arguments":"{\"cmd\":\"ls\"}"}
                ]
                """.trimIndent()
            )
        )
        assertEquals(3, segs.size)
        assertEquals(Segment.Reasoning("让我想想"), segs[0])
        assertEquals(Segment.Text("答案是 42"), segs[1])
        val tool = segs[2] as Segment.Tool
        assertEquals("c1", tool.call.id)
        assertEquals("bash", tool.call.name)
        assertEquals("""{"cmd":"ls"}""", tool.call.arguments)
        // 工具结果由后续 tool/result 事件回填，此处为空
        assertEquals("", tool.call.result)
    }

    @Test
    fun `空白块与未知块类型被丢弃`() {
        val segs = assistantMessageSegments(
            blocks(
                """
                [
                  {"type":"text","text":"   "},
                  {"type":"reasoning","text":""},
                  {"type":"image","attachment":{"sha256":"x"}},
                  {"type":"tool-result","toolCallId":"c1","content":[]},
                  {"type":"text","text":"真实正文"}
                ]
                """.trimIndent()
            )
        )
        assertEquals(listOf(Segment.Text("真实正文")), segs)
    }

    @Test
    fun `空或缺失 content 返回空列表`() {
        assertTrue(assistantMessageSegments(null).isEmpty())
        assertTrue(assistantMessageSegments(blocks("[]")).isEmpty())
    }

    @Test
    fun `无流式段时合并即最终内容（本次回归的主场景）`() {
        val incoming = assistantMessageSegments(
            blocks("""[{"type":"reasoning","text":"嗯"},{"type":"text","text":"你好呀"}]""")
        )
        val merged = mergeAssistantMessage(emptyList(), incoming)
        assertEquals(listOf(Segment.Reasoning("嗯"), Segment.Text("你好呀")), merged)
    }

    @Test
    fun `已有等价流式段时不重复插入`() {
        val existing = listOf(Segment.Reasoning("嗯"), Segment.Text("你好呀"))
        val incoming = assistantMessageSegments(
            blocks("""[{"type":"reasoning","text":"嗯"},{"type":"text","text":"你好呀"}]""")
        )
        assertEquals(existing, mergeAssistantMessage(existing, incoming))
    }

    @Test
    fun `流式半截文本被权威全文原地补全（不产生重复段）`() {
        // 上游某天又发 text-delta 时的形态：已累积 "上半句"，权威块是全文
        val existing = listOf(Segment.Text("上半句"))
        val incoming = assistantMessageSegments(blocks("""[{"type":"text","text":"上半句下半句"}]"""))
        assertEquals(
            listOf(Segment.Text("上半句下半句")),
            mergeAssistantMessage(existing, incoming),
        )
    }

    @Test
    fun `工具调用块按 id 对齐，结果回填不被覆盖`() {
        val withResult = listOf(
            Segment.Tool(ToolCallInfo(id = "c1", name = "bash", arguments = "{}", result = "ok")),
        )
        val incoming = assistantMessageSegments(
            blocks("""[{"type":"tool-call","id":"c1","name":"bash","arguments":"{}"},{"type":"text","text":"跑完了"}]""")
        )
        val merged = mergeAssistantMessage(withResult, incoming)
        assertEquals(2, merged.size)
        val tool = merged[0] as Segment.Tool
        assertEquals("ok", tool.call.result) // 已回填的结果保留
        assertEquals(Segment.Text("跑完了"), merged[1])
    }

    @Test
    fun `多步工具回合按序对齐，正文落位到末个工具之后`() {
        // 真机形态：T1 → R1 → T2 → 正文，权威 message 只含 T1/R1/T2（工具块是模型产的）
        val existing = listOf(
            Segment.Tool(ToolCallInfo(id = "c1", name = "bash", arguments = "{}", result = "a")),
            Segment.Reasoning("第一段思考"),
            Segment.Tool(ToolCallInfo(id = "c2", name = "bash", arguments = "{}", result = "b")),
            Segment.Text("最终正文"),
        )
        val incoming = assistantMessageSegments(
            blocks(
                """
                [
                  {"type":"tool-call","id":"c1","name":"bash","arguments":"{}"},
                  {"type":"reasoning","text":"第一段思考"},
                  {"type":"tool-call","id":"c2","name":"bash","arguments":"{}"}
                ]
                """.trimIndent()
            )
        )
        val merged = mergeAssistantMessage(existing, incoming)
        assertEquals(4, merged.size)
        assertEquals("第一段思考", (merged[1] as Segment.Reasoning).text)
        assertEquals("最终正文", (merged[3] as Segment.Text).text)
    }

    @Test
    fun `中断回合（缺首段）时仍按序补齐`() {
        val existing = listOf(Segment.Text("只剩这一句"))
        val incoming = assistantMessageSegments(
            blocks("""[{"type":"reasoning","text":"开头思考"},{"type":"text","text":"只剩这一句"}]""")
        )
        val merged = mergeAssistantMessage(existing, incoming)
        assertEquals(
            listOf(Segment.Reasoning("开头思考"), Segment.Text("只剩这一句")),
            merged,
        )
    }

    @Test
    fun `空 incoming 保持原样`() {
        val existing = listOf(Segment.Text("x"))
        assertEquals(existing, mergeAssistantMessage(existing, emptyList()))
    }
}
