package com.meow.academy.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 组件级主题扩展色（docs/design-dynamic-config.md §11.7）。
 *
 * 给「工具调用折叠条」「文件快捷栏」等特定 UI 提供独立色槽，
 * 不影响 Material3 [androidx.compose.material3.ColorScheme] 的整体色板。
 * 所有字段 null = 组件继续跟随主题色板对应色槽（如 secondaryContainer / primary）。
 *
 * 由 [MeowAcademyTheme] 从 theme-config.jsonc 的 `components` 解析后注入，
 * 组件通过 [LocalThemeExtras] 读取。
 */
data class ThemeExtras(
    /** 工具调用折叠条背景色；null = secondaryContainer */
    val toolGroupBackground: Color? = null,
    /** 工具调用折叠条图标/文字色；null = onSecondaryContainer */
    val toolGroupContent: Color? = null,
    /** 工具调用成功（✓）状态色；null = primary */
    val toolStatusColor: Color? = null,
    /** 文件面包屑导航文字/图标色；null = primary */
    val quickBarColor: Color? = null,
    /** 文件快捷栏（FilterChip）选中态容器色；null = primaryContainer */
    val quickBarSelectedContainer: Color? = null,
)

/** 组件级主题扩展色入口：由 [MeowAcademyTheme] 按 theme-config.jsonc 的 components 注入 */
val LocalThemeExtras = staticCompositionLocalOf { ThemeExtras() }
