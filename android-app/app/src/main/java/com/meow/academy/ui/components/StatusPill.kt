package com.meow.academy.ui.components

/**
 * 状态胶囊标签：短文本 + 可选前导圆点，用于终端连接状态、provider 启用状态等。
 *
 * Material You 细节：surfaceContainer 圆底 + 语义色文字，轻量不抢视线。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 状态胶囊。
 *
 * @param text 状态文案
 * @param container 胶囊背景色
 * @param content 文字/圆点颜色
 * @param leadingDot 是否显示前导「●」状态点
 */
@Composable
fun StatusPill(
    text: String,
    container: Color,
    content: Color,
    leadingDot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingDot) {
            Spacer(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(content),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}
