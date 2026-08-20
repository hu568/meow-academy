package com.meow.academy.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 文件类型图标配色（喵~）。
 *
 * 文件管理页按扩展名给不同类型的文件分配专属颜色；
 * 浅色用 Material 600 档、深色用 300/400 档，保证两种背景下都可读。
 */
data class FileTypeColors(
    val folder: Color,
    val markdown: Color,
    val text: Color,
    val image: Color,
    val audio: Color,
    val video: Color,
    val pdf: Color,
    val archive: Color,
    val code: Color,
    val json: Color,
    val html: Color,
    val database: Color,
    val apk: Color,
    val binary: Color,
)

/** 浅色主题下的文件类型色板（Material 600 档） */
val LightFileTypeColors = FileTypeColors(
    folder = Color(0xFFF9A825),   // Amber 700
    markdown = Color(0xFF1E88E5), // Blue 600
    text = Color(0xFF546E7A),     // BlueGrey 600
    image = Color(0xFF43A047),    // Green 600
    audio = Color(0xFF8E24AA),    // Purple 600
    video = Color(0xFFF4511E),    // DeepOrange 600
    pdf = Color(0xFFE53935),      // Red 600
    archive = Color(0xFF6D4C41),  // Brown 600
    code = Color(0xFF00897B),     // Teal 600
    json = Color(0xFF7CB342),     // LightGreen 600
    html = Color(0xFF3949AB),     // Indigo 600
    database = Color(0xFF00ACC1), // Cyan 600
    apk = Color(0xFF2E7D32),      // Green 800
    binary = Color(0xFF757575),   // Grey 600
)

/** 深色主题下的文件类型色板（Material 300/400 档） */
val DarkFileTypeColors = FileTypeColors(
    folder = Color(0xFFFFD54F),   // Amber 300
    markdown = Color(0xFF64B5F6), // Blue 300
    text = Color(0xFF90A4AE),     // BlueGrey 300
    image = Color(0xFF81C784),    // Green 300
    audio = Color(0xFFBA68C8),    // Purple 300
    video = Color(0xFFFF8A65),    // DeepOrange 300
    pdf = Color(0xFFE57373),      // Red 300
    archive = Color(0xFFA1887F),  // Brown 300
    code = Color(0xFF4DB6AC),     // Teal 300
    json = Color(0xFFAED581),     // LightGreen 300
    html = Color(0xFF7986CB),     // Indigo 300
    database = Color(0xFF4DD0E1), // Cyan 300
    apk = Color(0xFF66BB6A),      // Green 400
    binary = Color(0xFFBDBDBD),   // Grey 400
)

/** 文件类型配色入口：由 [MeowAcademyTheme] 按深浅主题注入 */
val LocalFileTypeColors = staticCompositionLocalOf { LightFileTypeColors }
