package com.meow.academy.rpc

/**
 * 模型管理数据模型（M4 可配置 provider）。
 * llm/providers、llm/models、llm/discoverModels、settings/setProvider 的响应/请求模型，
 * 由 DshRpcClient 解析、模型管理页使用。
 */

import kotlinx.serialization.Serializable

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

/** 模型的思考强度能力（llm/models 响应的 reasoning；session/setModel 响应的 modelReasoning 同构） */
@Serializable
data class LlmModelReasoning(
    /** 支持的思考档位（off/minimal/low/medium/high/xhigh/max，适配器声明顺序） */
    val efforts: List<String> = emptyList(),
    /** 适配器默认档；缺省 = 走 provider 自身默认 */
    val defaultEffort: String? = null,
)

/** 模型目录条目（llm/models、llm/discoverModels 响应） */
@Serializable
data class LlmModelInfo(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    /** 模型接受的输入模态（text / image）；null=未知，非空且不含 image=明确不支持图片 */
    val inputModalities: List<String>? = null,
    /** 思考档位能力；null=模型无思考声明（llm 核心会拒绝任何显式强度，含 off） */
    val reasoning: LlmModelReasoning? = null,
) {
    /** 是否支持图片输入（未知时返回 true，让发送端尝试后由后端裁决） */
    val supportsImage: Boolean get() = inputModalities?.contains("image") ?: true
}

/** 提交 provider 时的模型条目（settings/setProvider / settings/updateProviderModels 的 models） */
@Serializable
data class LlmModelInput(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val input: List<String>? = null,
    /**
     * 思考档位声明（key=档位，value=wire 拼写；off 等不发参数的档位 value=null）。
     * null=不声明（模型按无思考能力处理）；非空=模型支持思考，档位可被聊天页选用。
     */
    val reasoningEfforts: Map<String, String?>? = null,
)
