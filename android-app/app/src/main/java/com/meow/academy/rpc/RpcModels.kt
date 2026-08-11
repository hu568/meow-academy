package com.meow.academy.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * RPC 命令（写入 pi stdin，一行一个 JSON）。
 *
 * 只声明当前 M2 用到的字段；协议详见 <https://pi.dev/docs/latest/rpc>。
 */
@Serializable
data class RpcCommand(
    /** 命令名：prompt / steer / follow_up / abort / new_session / bash / abort_bash / set_model / get_state / get_commands */
    val type: String,
    /** 关联 id：若提供，对应 response 会带相同 id（bash_execution_update 也带） */
    val id: String? = null,
    /** prompt/steer/follow_up 的消息文本 */
    val message: String? = null,
    /** bash 要执行的命令 */
    val command: String? = null,
    /** 流式进行时 prompt 的排队策略：steer / followUp */
    val streamingBehavior: String? = null,
    /** set_model 的 provider 与模型 id */
    val provider: String? = null,
    val modelId: String? = null,
)

/** 命令的响应（type == "response"），成功时 data 为命令相关的 JSON 对象 */
@Serializable
data class PiResponse(
    val id: String? = null,
    val type: String = "response",
    val command: String,
    val success: Boolean,
    val data: JsonObject? = null,
    val error: String? = null,
)

/**
 * pi 推送的事件（stdout JSONL，type != "response"）。
 *
 * 事件字段随 pi 版本演进，这里保留原始 [JsonObject] 并暴露最常用的访问器，
 * 上层（聊天/终端）按需取用。
 */
class PiEvent(val raw: JsonObject) {

    val type: String get() = raw["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"

    /** 事件关联的请求 id（bash_execution_update / extension_ui_request 等） */
    val id: String? get() = raw["id"]?.jsonPrimitive?.contentOrNull

    /** 事件负载（若存在） */
    val data: JsonObject? get() = raw["data"] as? JsonObject

    /** message_update 的 assistantMessageEvent 子对象（text_delta/thinking_delta 等） */
    val assistantMessageEvent: JsonObject? get() = raw["assistantMessageEvent"] as? JsonObject

    override fun toString(): String = "PiEvent($type${id?.let { ", id=$it" } ?: ""})"
}

/** 事件类型常量（参考 pi RPC 文档） */
object PiEventTypes {
    const val AGENT_START = "agent_start"
    const val AGENT_END = "agent_end"
    const val AGENT_SETTLED = "agent_settled"
    const val TURN_START = "turn_start"
    const val TURN_END = "turn_end"
    const val MESSAGE_START = "message_start"
    const val MESSAGE_END = "message_end"
    const val MESSAGE_UPDATE = "message_update"
    const val BASH_EXECUTION_UPDATE = "bash_execution_update"
    const val TOOL_EXECUTION_START = "tool_execution_start"
    const val TOOL_EXECUTION_UPDATE = "tool_execution_update"
    const val TOOL_EXECUTION_END = "tool_execution_end"
    const val EXTENSION_UI_REQUEST = "extension_ui_request"
    const val EXTENSION_ERROR = "extension_error"
    const val QUEUE_UPDATE = "queue_update"

    // assistantMessageEvent 子类型
    const val ASSISTANT_TEXT_DELTA = "text_delta"
    const val ASSISTANT_THINKING_DELTA = "thinking_delta"
}

/** 便捷扩展：读 JsonObject 里的字符串字段 */
fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** 便捷扩展：读 JsonObject 里的布尔字段 */
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBoolean()

/** 便捷扩展：读 JsonObject 里的整数字段 */
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
