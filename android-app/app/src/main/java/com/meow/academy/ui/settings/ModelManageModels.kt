package com.meow.academy.ui.settings

/**
 * 模型管理共享 UI 数据与常量（Screen 与 ViewModel 共用）。
 * ProviderProfile / ModelProfile / DEEPSEEK_PROVIDER / slug() / PROVIDER_PRESETS
 * 已下沉到 data.model（聊天页与设置页共用的目录构建层），这里只保留 ProviderListItem。
 */

/** 模型管理列表条目（内置 DeepSeek + 常见 provider + 用户自定义） */
data class ProviderListItem(
    val key: String,
    val displayName: String,
    val baseURL: String?,
    val modelCount: Int,
    val registered: Boolean,
    val isBuiltin: Boolean,
)
