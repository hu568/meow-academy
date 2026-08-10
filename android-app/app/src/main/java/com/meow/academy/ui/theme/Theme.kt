package com.meow.academy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.meow.academy.data.settings.ThemeMode

/** 自定义模式用的浅色配色（猫娘粉紫） */
private val CustomLightColors = lightColorScheme(
    primary = MeowPinkLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = MeowAccent,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = MeowAccent,
    background = MeowBackgroundLight,
    surface = MeowSurfaceLight,
    error = MeowErrorLight,
)

/** 自定义模式用的深色配色 */
private val CustomDarkColors = darkColorScheme(
    primary = MeowPinkDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF3D2A4D),
    primaryContainer = MeowAccent,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = MeowPinkDark,
    background = MeowBackgroundDark,
    surface = MeowSurfaceDark,
    error = MeowErrorDark,
)

/**
 * 喵学堂主题入口。
 *
 * 四种模式：
 * - [ThemeMode.SYSTEM] 跟随系统深浅；Android 12+ 自动动态取色（Material You）
 * - [ThemeMode.LIGHT]  强制浅色；Android 12+ 动态取色
 * - [ThemeMode.DARK]   强制深色；Android 12+ 动态取色
 * - [ThemeMode.CUSTOM] 使用下方自定义猫娘粉紫色板（不参与动态取色），跟随系统深浅
 */
@Composable
fun MeowAcademyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.CUSTOM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        // 自定义配色：不用动态取色，用喵学堂专属色板
        themeMode == ThemeMode.CUSTOM ->
            if (darkTheme) CustomDarkColors else CustomLightColors

        // Android 12+ 动态取色（Material You）
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // 低版本回退默认 Material 配色
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
