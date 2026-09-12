package com.meow.academy.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回合分段归位单测（真机回归锁定）。
 *
 * 场景取自 **2026-09-12 真机 logcat + 落库 segmentsJson 实锤**（session room-194 / turn 2）：
 * ```
 * assistant/message(step1, [reasoning, tool-call bash]) → tool/call(bash) → tool/result(bash)
 * assistant/message(step2, [tool-call read])           → tool/call(read) → tool/result(read)
 * assistant/message(step3, [text 最终正文])            → turn/end
 * ```
 * 旧实现落库成 `[text, tool(read), reasoning, tool(bash), tool(bash 重复), tool(read 重复)]` ——
 * 末段正文跑到最上面、工具卡被挤到当前轮最下面、每次调用出现两条（UI 渲染「工具调用 x4」）。
 */
class TurnSegmentBuilderTest {

    private fun blocks(raw: String): JsonArray = Json.parseToJsonElement(raw) as JsonArray

    /** 只取类型序列，断言顺序用（tool 带上 callId 末位便于定位） */
    private fun shape(segs: List<Segment>): List<String> = segs.map {
        when (it) {
            is Segment.Reasoning -> "reasoning"
            is Segment.Text -> "text"
            is Segment.Tool -> "tool:" + it.call.id
        }
    }

    @Test
    fun `真机事件序 —— 最终正文落在末个工具之后，且工具段不重复`() {
        val b = TurnSegmentBuilder()
        // step 1：思考 + 调用 bash
        b.onAssistantMessage(
            2, 1,
            blocks(
                """
                [
                  {"type":"reasoning","text":"Simple. Create hello.txt in workspace"},
                  {"type":"tool-call","id":"call_bash","name":"bash","arguments":"{\"command\":\"echo hello\"}"}
                ]
                """.trimIndent()
            ),
        )
        b.onToolCall(2, 1, ToolCallInfo("call_bash", "bash", """{"command":"echo hello"}"""))
        b.onToolResult("call_bash", "-rw------- hello.txt", false)
        // step 2：只调用 read（无正文、无思考）
        b.onAssistantMessage(2, 2, blocks("""[{"type":"tool-call","id":"call_read","name":"read","arguments":"{}"}]"""))
        b.onToolCall(2, 2, ToolCallInfo("call_read", "read", "{}"))
        b.onToolResult("call_read", "hello from bash", false)
        // step 3：最终正文
        b.onAssistantMessage(2, 3, blocks("""[{"type":"text","text":"已用 bash 建好 hello.txt。"}]"""))

        val segs = b.segments()
        assertEquals(
            listOf("reasoning", "tool:call_bash", "tool:call_read", "text"),
            shape(segs),
        )
        // 结果回填仍然保留（tool/result 在权威 message 之后到达）
        assertEquals("-rw------- hello.txt", (segs[1] as Segment.Tool).call.result)
        assertEquals("hello from bash", (segs[2] as Segment.Tool).call.result)
        assertEquals("已用 bash 建好 hello.txt。", (segs[3] as Segment.Text).text)
    }

    @Test
    fun `tool call 事件先于权威 message 到达时同样归位到该 step 的块序`() {
        val b = TurnSegmentBuilder()
        // 乱序容忍：tool/call 先到（旧实现的推荐顺序假设），权威 message 后到必须把正文排到工具之前
        b.onToolCall(1, 1, ToolCallInfo("c1", "bash", "{}"))
        b.onToolResult("c1", "ok", false)
        b.onAssistantMessage(
            1, 1,
            blocks("""[{"type":"reasoning","text":"想一下"},{"type":"text","text":"开始干"},{"type":"tool-call","id":"c1","name":"bash","arguments":"{}"}]"""),
        )
        val segs = b.segments()
        assertEquals(listOf("reasoning", "text", "tool:c1"), shape(segs))
        assertEquals("ok", (segs[2] as Segment.Tool).call.result) // 已回填结果不被权威块覆盖
    }

    @Test
    fun `同一事件重复送达不产生重复段（幂等）`() {
        val b = TurnSegmentBuilder()
        val msg = blocks("""[{"type":"tool-call","id":"c1","name":"bash","arguments":"{}"}]""")
        repeat(3) {
            b.onAssistantMessage(1, 1, msg)
            b.onToolCall(1, 1, ToolCallInfo("c1", "bash", "{}"))
            b.onToolResult("c1", "ok", false)
        }
        assertEquals(listOf("tool:c1"), shape(b.segments()))
        assertEquals("ok", (b.segments()[0] as Segment.Tool).call.result)
    }

    @Test
    fun `tool result 先到时先建占位段，权威 message 到达后按 id 对齐补全`() {
        val b = TurnSegmentBuilder()
        b.onToolResult("c9", "结果先到", false)
        b.onAssistantMessage(1, 1, blocks("""[{"type":"tool-call","id":"c9","name":"read","arguments":"{\"file_path\":\"a.txt\"}"}]"""))
        val segs = b.segments()
        assertEquals(listOf("tool:c9"), shape(segs))
        val tool = (segs[0] as Segment.Tool).call
        assertEquals("read", tool.name)                 // 权威块补全工具名
        assertEquals("""{"file_path":"a.txt"}""", tool.arguments)
        assertEquals("结果先到", tool.result)             // 已到的结果保留
    }

    @Test
    fun `chunk 增量在权威 message 到达时原地补全（不产生重复正文）`() {
        val b = TurnSegmentBuilder()
        b.onChunkDelta(1, 1, "reasoning-delta", "上半句")
        b.onChunkDelta(1, 1, "text-delta", "正文")
        b.onChunkDelta(1, 1, "text-delta", "后半")
        b.onAssistantMessage(
            1, 1,
            blocks("""[{"type":"reasoning","text":"上半句下半句"},{"type":"text","text":"正文后半"}]"""),
        )
        val segs = b.segments()
        assertEquals(listOf("reasoning", "text"), shape(segs))
        assertEquals("上半句下半句", (segs[0] as Segment.Reasoning).text)
        assertEquals("正文后半", (segs[1] as Segment.Text).text)
    }

    @Test
    fun `缺 turn step 的事件沿用上一个 key，按到达顺序累积`() {
        val b = TurnSegmentBuilder()
        b.onAssistantMessage(1, 1, blocks("""[{"type":"text","text":"第一段"}]"""))
        b.onAssistantMessage(null, null, blocks("""[{"type":"text","text":"第二段"}]"""))
        assertEquals(listOf("text", "text"), shape(b.segments()))
        assertEquals("第一段", (b.segments()[0] as Segment.Text).text)
        assertEquals("第二段", (b.segments()[1] as Segment.Text).text)
    }

    @Test
    fun `多工具同 step：按权威块序排列，不按 tool call 事件到达序`() {
        val b = TurnSegmentBuilder()
        // 权威 message 里两个调用；tool/call 事件到达顺序刻意相反
        b.onAssistantMessage(
            1, 1,
            blocks(
                """
                [
                  {"type":"tool-call","id":"c1","name":"bash","arguments":"{}"},
                  {"type":"reasoning","text":"再读回来"},
                  {"type":"tool-call","id":"c2","name":"read","arguments":"{}"}
                ]
                """.trimIndent()
            ),
        )
        b.onToolCall(1, 1, ToolCallInfo("c2", "read", "{}"))
        b.onToolCall(1, 1, ToolCallInfo("c1", "bash", "{}"))
        b.onToolResult("c1", "a", false)
        b.onToolResult("c2", "b", false)
        assertEquals(listOf("tool:c1", "reasoning", "tool:c2"), shape(b.segments()))
        assertEquals("a", (b.segments()[0] as Segment.Tool).call.result)
        assertEquals("b", (b.segments()[2] as Segment.Tool).call.result)
    }

    @Test
    fun `空 incoming 不改动已有分段`() {
        val b = TurnSegmentBuilder()
        b.onAssistantMessage(1, 1, blocks("""[{"type":"text","text":"已有"}]"""))
        b.onAssistantMessage(1, 2, blocks("[]"))
        assertEquals(listOf("text"), shape(b.segments()))
    }
}
