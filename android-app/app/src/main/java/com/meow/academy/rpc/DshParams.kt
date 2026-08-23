package com.meow.academy.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 请求参数构造（只声明当前阶段用到的字段） */
object DshParams {

    /** initialize：进程级握手（cwd=workspace；provider/model 决定后续所有会话的路由） */
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

    /** session/stats：读某会话的调用量统计 */
    fun sessionStats(sessionId: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
    }

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

    /** settings/updateProviderModels：只更新 provider 的模型列表（配置页的 baseURL/Key 不受影响） */
    fun settingsUpdateProviderModels(
        provider: String,
        models: List<LlmModelInput>,
        expectedRevision: Int? = null,
    ): JsonObject = buildJsonObject {
        put("provider", provider)
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
