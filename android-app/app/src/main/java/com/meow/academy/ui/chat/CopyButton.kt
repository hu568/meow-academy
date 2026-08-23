package com.meow.academy.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Markdown 块级组件的通用复制按钮。
 *
 * 用于代码块 / 公式块 / 表格等块级组件，一键复制 Markdown 原文
 * （如 LaTeX 源码、代码文本等）。写入系统剪贴板后 Toast 提示「已复制喵~」。
 */
@Composable
fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("markdown", text))
            Toast.makeText(context, "已复制喵~", Toast.LENGTH_SHORT).show()
        },
        modifier = modifier
            .size(24.dp)
            .background(containerColor, RoundedCornerShape(4.dp)),
    ) {
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "复制",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}
