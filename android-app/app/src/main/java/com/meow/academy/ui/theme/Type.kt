package com.meow.academy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 喵仓全局排版规范（Material You 细节打磨）。
 *
 * 以 Material 3 默认 Typography 为基底（display/headline/title/body/label 全套层级
 * 均使用 M3 规范字重与字号），仅微调正文行高让长文本阅读更透气。
 * 后续若要整体换字体，只改这里一处。
 */
val Typography = Typography(
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.5.sp,
    ),
)
