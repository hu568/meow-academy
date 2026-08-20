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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.meow.academy.data.settings.DEFAULT_THEME_SEED_ARGB
import com.meow.academy.data.settings.ThemeMode

/**
 * 喵学堂主题入口。
 *
 * 四种模式：
 * - [ThemeMode.SYSTEM] 跟随系统深浅；Android 12+ 自动动态取色（Material You）
 * - [ThemeMode.LIGHT]  强制浅色；Android 12+ 动态取色
 * - [ThemeMode.DARK]   强制深色；Android 12+ 动态取色
 * - [ThemeMode.CUSTOM] 自定义配色：用 [themeSeedColor] 种子色自动派生整套浅/深色板
 *   （[customLightColorScheme] / [customDarkColorScheme]），跟随系统深浅
 *
 * @param themeSeedColor 用户自定义主题种子色（ARGB Long，仅 CUSTOM 模式使用）
 */
@Composable
fun MeowAcademyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeSeedColor: Long = DEFAULT_THEME_SEED_ARGB,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.CUSTOM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
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

    CompositionLocalProvider(
        LocalFileTypeColors provides if (darkTheme) DarkFileTypeColors else LightFileTypeColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
