package com.meow.academy.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DSH jsonrpc 协议模型（JSON-RPC 2.0 over stdio，newline-delimited）。
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

/** 请求参数构造（只声明当前阶段用到的字段） */
object DshParams {

    /** initialize：进程级握手（cwd=filesDir；provider/model 决定后续所有会话的路由） */
    fun initialize(
        cwd: String,
        provider: String,
        model: String,
        maxTokens: Int? = null,
        reasoningEffort: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("cwd", cwd)
            put("provider", provider)
            put("model", model)
            if (maxTokens != null) put("maxTokens", maxTokens)
            if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
        }

    /** session/prompt：入队一条用户消息（contentBlocks 只发 text 块） */
    fun prompt(sessionId: String, text: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("contentBlocks", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
    }

    /** session/cancel：停止该会话正在生成的回合 */
    fun cancel(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    /** session/setModel：运行时切换某会话的模型/思考强度（只更新传入的字段） */
    fun setModel(
        sessionId: String,
        provider: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
    ): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        if (provider != null) put("provider", provider)
        if (model != null) put("model", model)
        if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
    }

    /** session/bash：执行终端命令（workdir 为空时由服务端按 DSH_CWD 默认） */
    fun bash(requestId: String, command: String, workdir: String? = null, timeoutMs: Long? = null): JsonObject =
        buildJsonObject {
            put("requestId", requestId)
            put("command", command)
            if (workdir != null) put("workdir", workdir)
            if (timeoutMs != null) put("timeoutMs", timeoutMs)
        }

    /** session/bashCancel：中止正在运行的终端命令 */
    fun bashCancel(requestId: String): JsonObject = buildJsonObject { put("requestId", requestId) }

    /** shutdown：让服务端优雅退出 */
    fun shutdown(): JsonObject = buildJsonObject { }

    // ── 模型管理（可配置 provider，M4） ──

    /** llm/providers：列出可配置 provider 目录 */
    fun llmProviders(): JsonObject = buildJsonObject { }

    /** llm/models：某 provider 的模型目录 */
    fun llmModels(provider: String): JsonObject = buildJsonObject { put("provider", provider) }

    /** llm/discoverModels：测试连接 / 获取远端模型列表 */
    fun llmDiscoverModels(provider: String?, baseURL: String?, api: String?, apiKey: String?): JsonObject =
        buildJsonObject {
            if (provider != null) put("provider", provider)
            if (baseURL != null) put("baseURL", baseURL)
            if (api != null) put("api", api)
            if (apiKey != null) put("apiKey", apiKey)
        }

    /** settings/describe：读取某 namespace 的 redacted descriptor */
    fun settingsDescribe(ns: String? = null): JsonObject = buildJsonObject {
        if (ns != null) put("ns", ns)
    }

    /** settings/setProvider：写 provider profile + credential */
    fun settingsSetProvider(
        provider: String,
        displayName: String?,
        baseURL: String?,
        api: String?,
        models: List<LlmModelInput>,
        apiKey: String?,
        expectedRevision: Int? = null,
    ): JsonObject = buildJsonObject {
        put("provider", provider)
        if (displayName != null) put("displayName", displayName)
        if (baseURL != null) put("baseURL", baseURL)
        if (api != null) put("api", api)
        put("models", buildJsonArray {
            models.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    if (m.name != null) put("name", m.name)
                    if (m.contextWindow != null) put("contextWindow", m.contextWindow)
                    if (m.maxTokens != null) put("maxTokens", m.maxTokens)
                    if (m.input != null) put("input", buildJsonArray { m.input.forEach { add(JsonPrimitive(it)) } })
                })
            }
        })
        if (apiKey != null) put("apiKey", apiKey)
        if (expectedRevision != null) put("expectedRevision", expectedRevision)
    }

    /** settings/removeProvider：删除 provider profile + credential */
    fun settingsRemoveProvider(provider: String, expectedRevision: Int? = null): JsonObject = buildJsonObject {
        put("provider", provider)
        if (expectedRevision != null) put("expectedRevision", expectedRevision)
    }

    /** llm/testModel：对单个模型发最小 chat 请求测连通 */
    fun testModel(provider: String, model: String): JsonObject = buildJsonObject {
        put("provider", provider)
        put("model", model)
    }
}

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

// ── 模型管理（可配置 provider）数据模型 ──

/** 可配置 provider 目录条目（llm/providers 响应） */
@Serializable
data class LlmProviderInfo(
    val provider: String,
    val displayName: String,
    val settingsNs: String = "",
    val settingsPath: List<String> = emptyList(),
    val registered: Boolean = false,
)

/** 模型目录条目（llm/models、llm/discoverModels 响应） */
@Serializable
data class LlmModelInfo(
    val id: String,
    val name: String,
    val description: String? = null,
)

/** 提交 provider 时的模型条目（settings/setProvider 的 models） */
@Serializable
data class LlmModelInput(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val input: List<String>? = null,
)

/** 便捷扩展：读 JsonObject 里的字符串字段（非 primitive 时序列化为字符串，绝不抛异常） */
fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null -> null
    is JsonPrimitive -> v.contentOrNull
    else -> v.toString()
}

/** 便捷扩展：读 JsonObject 里的布尔字段（非 primitive 返回 null，绝不抛异常） */
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBoolean()

/** 便捷扩展：读 JsonObject 里的整数字段（非 primitive 返回 null，绝不抛异常） */
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
