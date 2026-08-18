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

/** 模型目录条目（llm/models、llm/discoverModels 响应） */
@Serializable
data class LlmModelInfo(
    val id: String,
    val name: String? = null,
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
