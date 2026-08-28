package com.meow.academy.data.settings

/**
 * 设置相关枚举的展示名映射（展示层归属，与枚举定义分离）。
 * HomeTab / ThemeMode / ResidentMode → 中文展示名，集中在此避免散落各页面。
 */

/** 枚举 → 展示名映射（集中在此，避免散落在各页面） */

fun HomeTab.displayName(): String = when (this) {
    HomeTab.CHAT -> "💬 聊天"
    HomeTab.FILES -> "📁 文件管理"
    HomeTab.SETTINGS -> "⚙️ 我的"
}

fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.CUSTOM -> "自定义"
    ThemeMode.CONFIG -> "动态配置"
}

fun ResidentMode.displayName(minutes: Int): String = when (this) {
    ResidentMode.OFF -> "关闭"
    ResidentMode.TIMED -> "有限保活（" + minutes + " 分钟）"
    ResidentMode.ALWAYS -> "一直常驻"
}
