package com.meow.academy.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DSH jsonrpc 协议帧模型（JSON-RPC 2.0 over stdio，newline-delimited）。
 *
 * 与 pi RPC 的区别：
 *  - 帧带 `jsonrpc: "2.0"`；请求有 id、响应回相同 id；通知只有 method 无 id；
 *  - 事件统一走 `session.event` 通知（event 对象内 `type` 区分），
 *    生命周期走 `session.status`（idle/running），终端输出走 `session.bashOutput`。
 *
 * 协议细节见 docs/decision-dsh-agent.md 与 PC PoC（.tmp/dsh-poc/）。
 */

/** 请求帧（写入 stdin） */
@Serializable
data class DshRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonObject? = null,
)

/** 响应帧（按 id 匹配） */
@Serializable
data class DshResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonObject? = null,
    val error: DshError? = null,
) {
    val ok: Boolean get() = error == null
}

@Serializable
data class DshError(val code: Int, val message: String)

/** 通知帧（服务端推送） */
@Serializable
data class DshNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null,
)

/** 通知方法名 */
object DshNotifMethods {
    const val SESSION_EVENT = "session.event"
    const val SESSION_STATUS = "session.status"
    const val BASH_OUTPUT = "session.bashOutput"
}

/** session.event 里的事件类型（event.type） */
object DshEventTypes {
    const val TURN_START = "turn/start"
    const val TURN_END = "turn/end"
    const val STEP_START = "step/start"
    const val STEP_END = "step/end"
    const val USER_MESSAGE = "user/message"
    const val ASSISTANT_CHUNK = "assistant/chunk"
    const val ASSISTANT_MESSAGE = "assistant/message"
    const val TOOL_CALL = "tool/call"
    const val TOOL_RESULT = "tool/result"
}

/** assistant/chunk 的 chunk.type 子类型 */
object DshChunkTypes {
    const val BLOCK_START = "block-start"
    const val TEXT_DELTA = "text-delta"
    const val REASONING_DELTA = "reasoning-delta"
    const val TOOL_CALL_DELTA = "tool-call-delta"
    const val BLOCK_END = "block-end"
    const val USAGE = "usage"
    const val FINISH = "finish"
}

/** turn/end 的 reason.kind */
object DshTurnEndKinds {
    const val COMPLETED = "completed"
    const val ABORTED = "aborted"
    const val BLOCKED = "blocked"
    const val ERROR = "error"
    const val MAX_TOKENS = "max-tokens"
    const val INTERRUPTED = "interrupted"
}
