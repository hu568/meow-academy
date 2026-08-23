package com.meow.academy.ui.chat

import android.graphics.Color as AndroidColor
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meow.academy.data.settings.MarkdownConfig
import io.noties.markwon.Markwon

/**
 * Markdown 块级公式组件（`$$…$$` 块公式）。
 *
 * 与 jlatexmath 位图渲染不同，这里用 Markwon 把 `$$\nlatex\n$$` 渲染成
 * Spanned 后塞进 TextView（文字可选择），外层用 Compose 画圆角背景 + 内边距。
 *
 * 复制按钮与代码块组件一样放在**顶部独立工具栏行**，避免浮层遮挡公式内容；
 * 公式解析失败时由 buildMarkwon 的 errorHandler 回退显示 LaTeX 原文。
 */
@Composable
fun MathBlockCompose(
    latex: String,
    modifier: Modifier = Modifier,
    config: MarkdownConfig,
    markwon: Markwon,   // io.noties.markwon.Markwon
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(config.formula.blockCornerRadiusDp.dp))
            .background(parseColor(config.formula.blockBackground) ?: Color.Transparent),
    ) {
        // 顶部工具栏：复制按钮独立成行（右上角），不遮挡公式内容
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CopyButton(
                text = latex,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            )
        }

        // 公式内容：Markwon 渲染 $$ 块 → Spanned（含 jlatexmath 异步位图或原文回退）
        val spanned = remember(latex) { markwon.toMarkdown("$$\n$latex\n$$") }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            factory = { context ->
                TextView(context).apply {
                    textSize = 15f
                    setTextIsSelectable(true)
                }
            },
            update = { view ->
                // 必须走 setParsedMarkdown：公式是 AsyncDrawable 异步加载，
                // 它会处理 AsyncDrawableScheduler 调度刷新；直接 setText 会导致公式图不显示
                markwon.setParsedMarkdown(view, spanned)
            },
        )
    }
}

/** 解析配置里的十六进制颜色；null / 非法值返回 null（调用方回退透明） */
private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        Color(AndroidColor.parseColor(hex))
    } catch (_: IllegalArgumentException) {
        null
    }
}
