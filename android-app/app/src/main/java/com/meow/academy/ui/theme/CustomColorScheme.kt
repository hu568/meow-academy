package com.meow.academy.ui.theme

/**
 * 自定义主题色板生成器（Material You 风格近似）。
 *
 * 底层只持久化一个「种子色」[Color]，这里用 HSV 变换自动派生整套浅色/深色
 * [ColorScheme]：主色 = 用户种子色（所见即所得），secondary/tertiary 做色相偏移，
 * 容器色/背景/表面按 Material You 的 tone 规则取亮暗。
 *
 * 选色体验：用户只挑一个主色，深浅两套配色自动生成，无需逐项调色。
 */

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** 由种子色生成浅色 Material You 风格色板 */
fun customLightColorScheme(seed: Color): ColorScheme {
    val (h, s, _) = seed.toHsvTriple()
    // 主色用用户所选种子色；文字色按相对亮度动态选深/白，保证对比度
    val onPrimary = if (seed.luminance() > 0.55f) Color(0xFF3D2A4D) else Color.White
    return lightColorScheme(
        primary = seed,
        onPrimary = onPrimary,
        primaryContainer = hsv(h, s * 0.40f, 0.92f),
        onPrimaryContainer = hsv(h, (s * 0.75f).coerceAtMost(0.85f), 0.28f),
        secondary = hsv((h + 40f) % 360f, 0.45f, 0.50f),
        onSecondary = Color.White,
        secondaryContainer = hsv((h + 40f) % 360f, 0.32f, 0.90f),
        onSecondaryContainer = hsv((h + 40f) % 360f, 0.65f, 0.22f),
        tertiary = hsv((h - 40f + 360f) % 360f, 0.45f, 0.50f),
        onTertiary = Color.White,
        tertiaryContainer = hsv((h - 40f + 360f) % 360f, 0.32f, 0.90f),
        onTertiaryContainer = hsv((h - 40f + 360f) % 360f, 0.65f, 0.22f),
        background = hsv(h, 0.05f, 0.99f),
        onBackground = hsv(h, 0.08f, 0.12f),
        surface = hsv(h, 0.05f, 0.99f),
        onSurface = hsv(h, 0.08f, 0.12f),
        surfaceVariant = hsv(h, 0.06f, 0.93f),
        onSurfaceVariant = hsv(h, 0.08f, 0.45f),
        surfaceTint = seed,
        inverseSurface = hsv(h, 0.10f, 0.22f),
        inverseOnSurface = hsv(h, 0.04f, 0.93f),
        inversePrimary = hsv(h, 0.60f, 0.82f),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = hsv(h, 0.08f, 0.50f),
        outlineVariant = hsv(h, 0.05f, 0.85f),
        scrim = Color.Black,
        surfaceBright = hsv(h, 0.05f, 0.99f),
        surfaceContainer = hsv(h, 0.06f, 0.95f),
        surfaceContainerHigh = hsv(h, 0.06f, 0.92f),
        surfaceContainerHighest = hsv(h, 0.06f, 0.89f),
        surfaceContainerLow = hsv(h, 0.05f, 0.97f),
        surfaceContainerLowest = Color.White,
    )
}

/** 由种子色生成深色 Material You 风格色板 */
fun customDarkColorScheme(seed: Color): ColorScheme {
    val (h, s, _) = seed.toHsvTriple()
    // 深色主色提亮到 80 tone（Material You 深色规范），保证暗底上可读
    return darkColorScheme(
        primary = hsv(h, (s * 0.75f).coerceAtMost(0.90f), 0.82f),
        onPrimary = hsv(h, 0.70f, 0.20f),
        primaryContainer = hsv(h, 0.50f, 0.34f),
        onPrimaryContainer = hsv(h, 0.30f, 0.92f),
        secondary = hsv((h + 40f) % 360f, 0.35f, 0.80f),
        onSecondary = hsv((h + 40f) % 360f, 0.60f, 0.20f),
        secondaryContainer = hsv((h + 40f) % 360f, 0.35f, 0.30f),
        onSecondaryContainer = hsv((h + 40f) % 360f, 0.20f, 0.92f),
        tertiary = hsv((h - 40f + 360f) % 360f, 0.35f, 0.80f),
        onTertiary = hsv((h - 40f + 360f) % 360f, 0.60f, 0.20f),
        tertiaryContainer = hsv((h - 40f + 360f) % 360f, 0.35f, 0.30f),
        onTertiaryContainer = hsv((h - 40f + 360f) % 360f, 0.20f, 0.92f),
        background = hsv(h, 0.06f, 0.11f),
        onBackground = hsv(h, 0.03f, 0.93f),
        surface = hsv(h, 0.05f, 0.13f),
        onSurface = hsv(h, 0.03f, 0.93f),
        surfaceVariant = hsv(h, 0.06f, 0.28f),
        onSurfaceVariant = hsv(h, 0.04f, 0.75f),
        surfaceTint = hsv(h, (s * 0.75f).coerceAtMost(0.90f), 0.82f),
        inverseSurface = hsv(h, 0.03f, 0.90f),
        inverseOnSurface = hsv(h, 0.08f, 0.20f),
        inversePrimary = hsv(h, 0.70f, 0.50f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = hsv(h, 0.05f, 0.60f),
        outlineVariant = hsv(h, 0.06f, 0.30f),
        scrim = Color.Black,
        surfaceDim = hsv(h, 0.05f, 0.13f),
        surfaceContainer = hsv(h, 0.05f, 0.16f),
        surfaceContainerHigh = hsv(h, 0.05f, 0.18f),
        surfaceContainerHighest = hsv(h, 0.05f, 0.20f),
        surfaceContainerLow = hsv(h, 0.05f, 0.14f),
        surfaceContainerLowest = hsv(h, 0.06f, 0.09f),
    )
}

/** HSV → Color（h 0..360，s/v 0..1） */
private fun hsv(h: Float, s: Float, v: Float): Color =
    Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

/** Color → HSV 三元组（RGB 分量直接换算，h 0..360，s/v 0..1） */
private fun Color.toHsvTriple(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val hue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max == 0f) 0f else d / max
    return Triple(hue, saturation, max)
}
