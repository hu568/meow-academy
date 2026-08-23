package com.meow.academy.ui.chat

import com.meow.academy.rpc.LlmProviderInfo

/** 模型短名：内置 DeepSeek 显示 v4-flash / v4-pro，其余原样 */
internal fun modelLabel(model: String): String = when (model) {
    "deepseek-v4-flash" -> "v4-flash"
    "deepseek-v4-pro" -> "v4-pro"
    else -> model
}

/** provider 展示名：目录里找不到时回退原始 id */
internal fun providerLabel(provider: String, providers: List<LlmProviderInfo>): String =
    providers.firstOrNull { it.provider == provider }?.displayName ?: provider

/** 思考强度中文标签（工具栏与快捷面板共用） */
internal fun effortLabel(effort: String): String = when (effort) {
    "off" -> "关闭思考"
    "high" -> "高"
    "max" -> "最强"
    else -> effort
}