package com.meow.academy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.meow.academy.data.settings.DEFAULT_THEME_SEED_ARGB
import com.meow.academy.data.settings.ThemeConfigRaw
import com.meow.academy.data.settings.ThemeMode
import com.meow.academy.data.settings.resolveThemeConfig

/**
 * 当前实际应用的深浅色标记。
 *
 * 不能用 `isSystemInDarkTheme()` 代替：App 支持强制浅色/深色（ThemeMode.LIGHT/DARK），
 * 渲染层（Markdown 高亮、mermaid 主题、图片色调等）需要跟随「实际主题」而不是系统。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 喵仓主题入口。
 *
 * 五种模式：
 * - [ThemeMode.SYSTEM] 跟随系统深浅；Android 12+ 自动动态取色（Material You）
 * - [ThemeMode.LIGHT]  强制浅色；Android 12+ 动态取色
 * - [ThemeMode.DARK]   强制深色；Android 12+ 动态取色
 * - [ThemeMode.CUSTOM] 自定义配色：用 [themeSeedColor] 种子色自动派生整套浅/深色板
 *   （[customLightColorScheme] / [customDarkColorScheme]），跟随系统深浅
 * - [ThemeMode.CONFIG] 动态配置配色：读 appconfig/theme-config.jsonc（[themeConfigRaw]），
 *   种子色 + 具体色槽双重覆盖（[buildConfigColorScheme]），FileObserver 热更实时换肤，跟随系统深浅
 *
 * @param themeSeedColor 用户自定义主题种子色（ARGB Long，仅 CUSTOM 模式使用）
 * @param themeConfigRaw theme-config.jsonc 深合并后的原始配置（仅 CONFIG 模式使用；null = 全默认）
 */
@Composable
fun MeowAcademyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeSeedColor: Long = DEFAULT_THEME_SEED_ARGB,
    themeConfigRaw: ThemeConfigRaw? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.CUSTOM -> isSystemInDarkTheme()
        ThemeMode.CONFIG -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        // 动态配置配色：theme-config.jsonc 种子色 + 具体色槽双重覆盖（热更实时生效）
        themeMode == ThemeMode.CONFIG -> {
            buildConfigColorScheme(themeConfigRaw, darkTheme, DEFAULT_THEME_SEED_ARGB.toInt())
        }

        // 自定义配色：用户种子色 → Material You 风格深浅两套
        themeMode == ThemeMode.CUSTOM -> {
            val seed = Color(themeSeedColor.toInt())
            if (darkTheme) customDarkColorScheme(seed) else customLightColorScheme(seed)
        }

        // Android 12+ 动态取色（Material You）
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // 低版本回退默认 Material 配色
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    // 组件级扩展色（工具折叠条 / 文件快捷栏）：解析 theme-config.jsonc 的 components
    val extras = remember(themeConfigRaw, darkTheme) {
        val cfg = resolveThemeConfig(themeConfigRaw, darkTheme)
        ThemeExtras(
            toolGroupBackground = cfg.components.toolGroupBackground?.let(::parseHexColor),
            toolGroupContent = cfg.components.toolGroupContent?.let(::parseHexColor),
            toolStatusColor = cfg.components.toolStatusColor?.let(::parseHexColor),
            quickBarColor = cfg.components.quickBarColor?.let(::parseHexColor),
            quickBarSelectedContainer = cfg.components.quickBarSelectedContainer?.let(::parseHexColor),
        )
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalFileTypeColors provides if (darkTheme) DarkFileTypeColors else LightFileTypeColors,
        LocalThemeExtras provides extras,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
