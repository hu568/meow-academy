package com.meow.academy.data.model

/**
 * 模型目录数据模型 + 解析（前后端解耦的本地缓存层）。
 *
 * ProviderProfile / ModelProfile 从 ui/settings/ModelManageModels.kt 挪到这里，
 * 供聊天页工具栏与模型管理页共用：两页都从 DSH settingsDescribe("llm-pi-ai") 的
 * result（value.providers.<name>）解析出各自的视图数据。
 */

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** provider 配置 profile（settingsDescribe value.providers.<name>） */
data class ProviderProfile(
    val displayName: String? = null,
    val api: String? = null,
    val baseURL: String? = null,
    val models: List<ModelProfile> = emptyList(),
)

/** provider 下单个模型的扩展配置 */
data class ModelProfile(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
)

// ── 聊天页与设置页共用的 provider 目录 ──

/** 内置 DeepSeek 官方的 DSH provider 路由名（llm-deepseek 注册；两页共用同一 key） */
const val DEEPSEEK_PROVIDER = "deepseek-official"

/** provider 显示名 → 路由名（settings dict key）的 slug 化（两页共用，保证 key 一致） */
fun slug(name: String): String =
    name.lowercase().trim().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "provider" }

/** 常见 OpenAI 兼容 provider 预设（列表页直接展示，baseURL 预填；key = slug(displayName)） */
data class ProviderPreset(val displayName: String, val baseURL: String) {
    val key: String get() = slug(displayName)
}

/** 常见 OpenAI 兼容 provider 预设（与设置页展示顺序一致） */
val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset("OpenAI", "https://api.openai.com/v1"),
    ProviderPreset("Moonshot (Kimi)", "https://api.moonshot.cn/v1"),
    ProviderPreset("Groq", "https://api.groq.com/openai/v1"),
    ProviderPreset("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1"),
    ProviderPreset("通义千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
)

/** 提供商目录条目（聊天工具栏与设置页共用的同一份列表） */
data class ProviderDirectoryEntry(
    val key: String,
    val displayName: String,
    val registered: Boolean,
)

/**
 * 构建聊天页与设置页共用的 provider 目录：
 * 内置 DeepSeek + 常见预设（未配置也列出，用 [ProviderDirectoryEntry.registered] 区分）
 * + 已配置的自定义 provider。
 *
 * 两页都从这一个函数取列表，保证 provider 的 key 与显示名永远一致——
 * 不使用 DSH 的 llm/providers 完整 pi-ai 目录（那里是原始路由 id，与设置页的
 * 友好名/预设 key 对不上）。
 */
fun buildProviderDirectory(
    profiles: Map<String, ProviderProfile>,
    disabled: Set<String> = emptySet(),
): List<ProviderDirectoryEntry> {
    val list = mutableListOf(
        ProviderDirectoryEntry(DEEPSEEK_PROVIDER, "DeepSeek", registered = true),
    )
    for (preset in PROVIDER_PRESETS) {
        val profile = profiles[preset.key]
        list += ProviderDirectoryEntry(
            key = preset.key,
            displayName = profile?.displayName ?: preset.displayName,
            registered = profile != null,
        )
    }
    for ((key, profile) in profiles) {
        if (key == DEEPSEEK_PROVIDER || PROVIDER_PRESETS.any { it.key == key }) continue
        list += ProviderDirectoryEntry(key, profile.displayName ?: key, registered = true)
    }
    return list.filter { it.key !in disabled }
}

/**
 * 解析 settingsDescribe("llm-pi-ai") 的 result 为 provider 名 → [ProviderProfile]。
 * result 结构：namespaces[].value.providers.<name> = { displayName, api, baseURL, models[] }。
 */
fun parseCatalogProfiles(result: JsonObject?): Map<String, ProviderProfile> {
    if (result == null) return emptyMap()
    val namespaces = result["namespaces"]?.jsonArray ?: return emptyMap()
    val ns = namespaces.firstOrNull()?.jsonObject ?: return emptyMap()
    val value = ns["value"]?.jsonObject ?: return emptyMap()
    val providers = value["providers"]?.jsonObject ?: return emptyMap()
    val map = mutableMapOf<String, ProviderProfile>()
    for ((name, profileEl) in providers) {
        val p = profileEl.jsonObject
        val models = p["models"]?.jsonArray?.mapNotNull { m ->
            val mo = m.jsonObject
            val id = mo["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ModelProfile(
                id = id,
                name = mo["name"]?.jsonPrimitive?.content,
                contextWindow = mo["contextWindow"]?.jsonPrimitive?.content?.toIntOrNull(),
                maxTokens = mo["maxTokens"]?.jsonPrimitive?.content?.toIntOrNull(),
            )
        } ?: emptyList()
        map[name] = ProviderProfile(
            displayName = p["displayName"]?.jsonPrimitive?.content,
            api = p["api"]?.jsonPrimitive?.content,
            baseURL = p["baseURL"]?.jsonPrimitive?.content,
            models = models,
        )
    }
    return map
}
