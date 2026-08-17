package com.meow.academy.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 服务端推送事件（session.event / session.status / session.bashOutput 的解析视图）。
 *
 * 事件字段随 DSH 版本演进，这里保留原始 [JsonObject] 并暴露常用访问器，
 * 上层（聊天/终端）按需取用、按 sessionId/requestId 过滤。
 */
class DshEvent private constructor(
    val method: String,
    val params: JsonObject,
) {

    /** session.event / session.status 的会话 id */
    val sessionId: String? get() = params.str("sessionId")

    /** session.status 的整机状态（idle / running） */
    val status: String? get() = params.str("status")

    /** session.bashOutput 关联的请求 id */
    val requestId: String? get() = params.str("requestId")

    /** session.bashOutput 的增量输出 */
    val delta: String? get() = params.str("delta")

    /** session.event 的 event 对象 */
    val event: JsonObject? get() = params["event"] as? JsonObject

    /** event.type（session.event 才有；其余方法返回方法名） */
    val type: String get() = event?.str("type") ?: method

    /** event.data */
    val data: JsonObject? get() = event?.get("data") as? JsonObject

    /** assistant/chunk 的 chunk 对象 */
    val chunk: JsonObject? get() = data?.get("chunk") as? JsonObject

    /** tool/call 的 callId */
    val toolCallId: String? get() = data?.str("callId")

    /** tool/call 的工具名 */
    val toolName: String? get() = data?.str("name")

    /** tool/call 的原始参数字符串 */
    val toolArguments: String? get() = data?.str("arguments")

    /** tool/result 的 callId（data.message.content[0].toolCallId；与 tool/call 的顶层 callId 不同） */
    val toolResultCallId: String?
        get() {
            val message = data?.get("message") as? JsonObject ?: return null
            val content = message["content"] as? JsonArray ?: return null
            val block = content.firstOrNull() as? JsonObject ?: return null
            return block.str("toolCallId")
        }

    /** tool/result 的文本结果（data.message.content[0].content 里第一个 text 块） */
    val toolResultText: String?
        get() {
            val message = data?.get("message") as? JsonObject ?: return null
            val messageContent = message["content"] as? JsonArray ?: return null
            val block = messageContent.firstOrNull() as? JsonObject ?: return null
            val blocks = block["content"] as? JsonArray ?: return null
            return blocks.firstOrNull { (it as? JsonObject)?.str("type") == "text" }
                ?.let { (it as JsonObject).str("text") }
        }

    /** tool/result 是否失败（content[0].isError 或顶层 error 字段存在） */
    val toolResultIsError: Boolean
        get() {
            if (data?.get("error") != null) return true
            val message = data?.get("message") as? JsonObject ?: return false
            val messageContent = message["content"] as? JsonArray ?: return false
            val block = messageContent.firstOrNull() as? JsonObject ?: return false
            return block.bool("isError") == true
        }

    /** turn/end 的结束原因 kind（completed / aborted / error / max-tokens …） */
    val turnEndKind: String? get() = (data?.get("reason") as? JsonObject)?.str("kind")

    override fun toString(): String {
        val sess = sessionId?.let { ", session=$it" } ?: ""
        return "DshEvent($method type=$type$sess)"
    }

    companion object {
        fun from(obj: JsonObject): DshEvent {
            val method = obj.str("method") ?: "unknown"
            val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
            return DshEvent(method, params)
        }
    }
}
