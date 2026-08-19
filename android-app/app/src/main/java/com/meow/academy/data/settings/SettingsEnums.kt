package com.meow.academy.data.settings

/**
 * 主题模式
 *
 * - [SYSTEM] 跟随系统深浅（Android 12+ 自动动态取色）
 * - [LIGHT]  强制浅色
 * - [DARK]   强制深色
 * - [CUSTOM] 自定义配色：用户种子色（theme_seed_color）自动派生整套浅/深色板，跟随系统深浅
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, CUSTOM }

/** 底部导航三个板块，同时用作「默认首页」的候选 */
enum class HomeTab { CHAT, FILES, SETTINGS }

/**
 * 常驻三档开关（决策见 docs/decision-local-pi-agent.md §2.4）
 *
 * - [OFF]    关闭：不常驻
 * - [TIMED]  有限保活：后台保留 [minutes] 分钟后释放
 * - [ALWAYS] 一直常驻：前台服务驻留
 */
enum class ResidentMode { OFF, TIMED, ALWAYS }
