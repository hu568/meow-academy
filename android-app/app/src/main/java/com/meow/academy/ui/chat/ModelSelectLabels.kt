package com.meow.academy.ui.chat

import com.meow.academy.rpc.LlmProviderInfo

/** 模型短名：内置 DeepSeek 显示 v4-flash / v4-pro / v4-flash 视觉，其余原样 */
internal fun modelLabel(model: String): String = when (model) {
    "deepseek-v4-flash" -> "v4-flash"
    "deepseek-v4-pro" -> "v4-pro"
    "deepseek-v4-flash-vision-exp" -> "v4-flash 视觉"
    else -> model
}

/** provider 展示名：目录里找不到时回退原始 id */
internal fun providerLabel(provider: String, providers: List<LlmProviderInfo>): String =
    providers.firstOrNull { it.provider == provider }?.displayName ?: provider

/**
 * 思考强度中文标签（工具栏、快捷面板与模型管理对话框共用）。
 * 全集对齐 pi-ai ModelThinkingLevel；未知档位回退原文。
 */
internal fun effortLabel(effort: String): String = when (effort) {
    "off" -> "关闭思考"
    "minimal" -> "极简"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "最强"
    else -> effort
}