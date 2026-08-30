package com.meow.academy.rpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 请求参数构造（只声明当前阶段用到的字段） */
object DshParams {

    /** attachImages 的单张图片输入（canonical base64 + mediaType + 可选名称） */
    @Serializable
    data class ImageUpload(
        val mediaType: String,
        val data: String,
        val name: String? = null,
    )

    /** session/prompt 的 content block：text 或 image（image 的 attachment 是 attachImages 返回的 durable ref） */
    @Serializable
    data class ContentBlock(
        val type: String,
        val text: String? = null,
        val attachment: JsonObject? = null,
    )

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

    /**
     * session/prompt：入队一条用户消息（纯文本版，兼容旧调用）。
     * presetId / cwd 随参数顶层携带（plan-standard-mode §3.4）：会话未建时定死归属（Agent 预设 + 工作区），
     * 会话已存在时服务端忽略（以日志为唯一事实源），多传无害。
     */
    fun prompt(sessionId: String, text: String, presetId: String? = null, cwd: String? = null): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("contentBlocks", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        if (presetId != null) put("presetId", presetId)
        if (cwd != null) put("cwd", cwd)
    }

    /**
     * session/prompt：入队一条用户消息（contentBlocks 支持 text + image 混合块）。
     * presetId / cwd 同纯文本版（§3.4）。
     */
    fun prompt(sessionId: String, blocks: List<ContentBlock>, presetId: String? = null, cwd: String? = null): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("contentBlocks", buildJsonArray {
            blocks.forEach { block ->
                add(buildJsonObject {
                    put("type", block.type)
                    if (block.text != null) put("text", block.text)
                    if (block.attachment != null) put("attachment", block.attachment)
                })
            }
        })
        if (presetId != null) put("presetId", presetId)
        if (cwd != null) put("cwd", cwd)
    }

    /** session/attachImages：canonical base64 图片批 → 附件服务规范化入库 → durable refs */
    fun attachImages(images: List<ImageUpload>): JsonObject = buildJsonObject {
        put("images", buildJsonArray {
            images.forEach { img ->
                add(buildJsonObject {
                    put("mediaType", img.mediaType)
                    put("data", img.data)
                    if (img.name != null) put("name", img.name)
                })
            }
        })
    }

    /** session/imageLimits：图片限额（App 端压缩参数上限预取用） */
    fun imageLimits(): JsonObject = buildJsonObject { }

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

    // ── Agent 预设 / 附加模式 / 问答（普通模式补完，plan-standard-mode §三） ──

    /** presets/list：列 Agent 预设（自动扫描接口，请求为空对象） */
    fun presetsList(): JsonObject = buildJsonObject { }

    /** presets/read：读某预设的组合文件全文（创造预设用） */
    fun presetsRead(id: String): JsonObject = buildJsonObject {
        put("id", id)
    }

    /** presets/delete：删自定义预设（仅 trust=user 可删；内置预设服务端抛 PRESET_IMMUTABLE） */
    fun presetsDelete(id: String): JsonObject = buildJsonObject {
        put("id", id)
    }

    /**
     * session/command：程序化执行斜杠命令（/plan、/goal…；附加模式胶囊的执行通道）。
     * presetId / cwd 与 prompt 同款可选携带——会话未建时先创建并定死归属（§3.5）。
     */
    fun sessionCommand(sessionId: String, line: String, presetId: String? = null, cwd: String? = null): JsonObject =
        buildJsonObject {
            put("sessionId", sessionId)
            put("line", line)
            if (presetId != null) put("presetId", presetId)
            if (cwd != null) put("cwd", cwd)
        }

    /** session/answerQuestion 的单条回答（id 对应问题 id；selected 为选中选项 label；custom 为自由文本回答） */
    @Serializable
    data class QuestionAnswer(
        val id: String,
        val selected: List<String> = emptyList(),
        val custom: String? = null,
    )

    /** session/answerQuestion：回答问答（cancelled=true 表示用户取消，此时忽略 answers） */
    fun answerQuestion(
        requestId: String,
        answers: List<QuestionAnswer> = emptyList(),
        cancelled: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("requestId", requestId)
        if (cancelled) {
            put("cancelled", true)
        } else {
            put("answers", buildJsonArray {
                answers.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        put("selected", buildJsonArray { a.selected.forEach { add(JsonPrimitive(it)) } })
                        if (a.custom != null) put("custom", a.custom)
                    })
                }
            })
        }
    }

    /** session/query：读旧会话状态水合（preset / blank / todos / plan / goal，resume 后胶囊与悬浮栏用） */
    fun sessionQuery(sessionId: String): JsonObject = buildJsonObject {
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
