package com.meow.academy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 喵仓全局圆角规范（Material You 风格，比 M3 默认更圆润）。
 *
 * - extraSmall 6dp：小胶囊/状态点
 * - small      10dp：输入框、小型组件
 * - medium     16dp：气泡、列表项
 * - large      20dp：卡片
 * - extraLarge 28dp：对话框、底部面板
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
