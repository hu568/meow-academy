package com.meow.academy.ui.settings

/**
 * 模型管理共享 UI 数据与常量（Screen 与 ViewModel 共用）。
 * ProviderProfile / ModelProfile 已下沉到 data.model（前后端解耦的缓存层），
 * 这里保留 ProviderListItem + slug() / PRESETS / DEEPSEEK_PROVIDER。
 */

/** 内置 DeepSeek 官方的 DSH provider 路由名 */
const val DEEPSEEK_PROVIDER = "deepseek-official"

/** 模型管理列表条目（内置 DeepSeek + 常见 provider + 用户自定义） */
data class ProviderListItem(
    val key: String,
    val displayName: String,
    val baseURL: String?,
    val modelCount: Int,
    val registered: Boolean,
    val isBuiltin: Boolean,
)

/** provider 显示名 → 路由名（settings dict key）的 slug 化 */
fun slug(name: String): String =
    name.lowercase().trim().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "provider" }

/** 常见 OpenAI 兼容 provider 预设（直接展示在列表，baseURL 预填） */
val PRESETS: List<Pair<String, String>> = listOf(
    "OpenAI" to "https://api.openai.com/v1",
    "Moonshot (Kimi)" to "https://api.moonshot.cn/v1",
    "Groq" to "https://api.groq.com/openai/v1",
    "硅基流动 SiliconFlow" to "https://api.siliconflow.cn/v1",
    "通义千问 Qwen" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
)
