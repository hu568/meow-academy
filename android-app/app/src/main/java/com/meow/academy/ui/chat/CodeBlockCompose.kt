package com.meow.academy.ui.chat

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meow.academy.data.settings.MarkdownConfig
import io.noties.markwon.syntax.Prism4jSyntaxHighlight
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.prism4j.Prism4j
import android.graphics.Color as AndroidColor

/**
 * M5 代码块组件：语言标签 + 复制按钮 + 横向滚动 + Prism4j 语法高亮。
 *
 * 结构：
 * - 外层 [Column] 负责圆角背景（优先取 `config.code.blockBackground`，否则半透明 surfaceContainerLow）；
 * - 顶部工具栏显示语言标签（[language] 为 null 时不显示）与 [CopyButton]（复制纯代码）；
 * - 代码区用 [Row] + [horizontalScroll] 实现横向滚动，内部 [AndroidView] 承载 TextView，
 *   经 [Prism4jSyntaxHighlight] 渲染语法高亮后的 [CharSequence]。
 *
 * @param language 围栏 info 串第一个 token；null 表示无语言，不显示标签且不做高亮
 * @param code 代码原文
 * @param modifier 外部传入的 Modifier
 * @param isDark 是否深色主题（决定 Prism4j 主题：Darkula / Default）
 * @param config Markdown 渲染配置（圆角、背景、字号比例）
 */
@Composable
fun CodeBlockCompose(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    config: MarkdownConfig,
) {
    val cornerShape = RoundedCornerShape(config.code.blockCornerRadiusDp.dp)
    val backgroundColor = parseColor(config.code.blockBackground)
        ?: MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)

    // 代码文字基础色：配置优先（可 { light, dark } 主题感知），否则跟随当前实际主题
    // （浅色 = 深字、深色 = 浅字）。纯文本代码块（无语言标记）没有 Prism4j 高亮 span，
    // 全靠 TextView 的 textColor 上色，不显式设置会在运行时切换明暗主题后残留旧色
    // （如深色主题创建出的白色文字，切到浅色后浅底白字看不清）。
    val textColor = parseColor(config.code.blockTextColor)
        ?: MaterialTheme.colorScheme.onSurface

    // Prism4j 实例只建一次；主题随深浅色切换重建
    val prism4j = remember { Prism4j(MarkdownGrammarLocator()) }
    val theme = remember(isDark) {
        if (isDark) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
    }
    val syntaxHighlight = remember(prism4j, theme) {
        Prism4jSyntaxHighlight.create(prism4j, theme)
    }
    // language 为 null 时 Prism4jSyntaxHighlight 直接返回原码
    val highlighted = remember(language, code, syntaxHighlight) {
        syntaxHighlight.highlight(language, code)
    }

    Column(
        modifier = modifier
            .clip(cornerShape)
            .background(backgroundColor),
    ) {
        // 顶部工具栏：左语言标签，右复制按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (language != null) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            CopyButton(text = code)
        }

        // 代码区：横向滚动 + TextView（等宽字体、Prism4j 高亮、可长按选择）
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    }
                },
                update = { view ->
                    view.textSize = 15f * config.code.blockTextSizeRatio
                    view.setTextColor(textColor.toArgb())
                    view.setText(highlighted)
                },
            )
        }
    }
}

/**
 * 解析 Markdown 配置里的十六进制颜色字符串（"#RRGGBB" / "#AARRGGBB"）。
 *
 * 解析失败或 null 时返回 null，调用方回退主题默认色。
 */
private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
}
