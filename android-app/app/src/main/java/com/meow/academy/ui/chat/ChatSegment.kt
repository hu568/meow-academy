package com.meow.academy.ui.chat

/**
 * 聊天步骤模型：思考段 / 文本段 / 工具调用按 DSH 事件到达顺序交错渲染。
 * UI（ChatScreen 系）与 ChatViewModel 共享。
 */
data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String = "",
    val result: String = "",
    val isError: Boolean = false,
)

/** 有序步骤：思考段 / 文本段 / 工具调用 */
sealed interface Segment {
    data class Reasoning(val text: String) : Segment
    data class Text(val text: String) : Segment
    data class Tool(val call: ToolCallInfo) : Segment
}

/**
 * 流式消息的实时状态（全 val 不可变）。
 * StateFlow 用 equals 判等去重，同一实例原地改字段再回写不会触发 emit，
 * 必须 copy 出新对象（与 TerminalEntry 同理，见踩坑记录 #5）。
 */
data class StreamingState(
    val messageId: Long,
    val segments: List<Segment> = emptyList(),
)
