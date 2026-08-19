package com.meow.academy.ui.components

/**
 * 统一卡片组件：Material You 风格的圆角卡片容器。
 *
 * 默认 elevated（柔和阴影），可点击时带按压反馈；用于设置分组、provider/模型卡片等。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 统一卡片。
 *
 * @param onClick 非 null 时整卡可点击（带 M3 按压反馈）
 * @param modifier 外部修饰
 * @param content 卡片内容
 */
@Composable
fun AppCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            content = content,
        )
    } else {
        Card(
            modifier = modifier.clickable(onClick = onClick),
            shape = shape,
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            content = content,
        )
    }
}
