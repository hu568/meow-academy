package com.meow.academy.ui.chat

import android.content.Context
import android.util.TypedValue
import com.meow.academy.data.settings.MarkdownConfig
import com.meow.academy.data.settings.themeSeedFromHex
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock

/**
 * 把 [MarkdownConfig] 应用到 Markwon 主题 / Span 工厂。
 *
 * 负责「施工队」里与 Markwon 主题相关的部分：
 * - 列表 · 的粗细/大小/颜色（`-` 渲染的圆点）；
 * - 代码块背景色/边距/字号/文字色 + 圆角（经 [RoundedCodeBlockSpan]）；
 * - 引用块颜色/宽度、链接颜色/下划线、标题字号倍率、分割线颜色/高度。
 *
 * 公式块（`$$…$$`）的圆角/背景/内边距由 [buildMarkwon] 里的
 * `JLatexMathTheme.Builder` 配置（blockBackgroundProvider），不在这里。
 */
class MarkdownConfigPlugin(
    private val context: Context,
    private val textSizePx: Float,
    private val config: MarkdownConfig,
) : AbstractMarkwonPlugin() {

    override fun configureTheme(builder: MarkwonTheme.Builder) {
        // 无序列表「-」渲染的 · 大小
        with(config.list) {
            builder.bulletWidth(dp(bulletWidthDp))
            builder.bulletListItemStrokeWidth(dp(bulletStrokeWidthDp))
            itemColor?.let { builder.listItemColor(parseColor(it)) }
        }

        // 代码块 / 行内代码
        with(config.code) {
            blockBackground?.let { builder.codeBlockBackgroundColor(parseColor(it)) }
            blockTextColor?.let { builder.codeBlockTextColor(parseColor(it)) }
            textColor?.let { builder.codeTextColor(parseColor(it)) }
            builder.codeBlockMargin(dp(blockMarginDp))
            builder.codeBlockTextSize((textSizePx * blockTextSizeRatio).toInt())
            builder.codeTextSize((textSizePx * textSizeRatio).toInt())
        }

        // 引用块
        with(config.quote) {
            color?.let { builder.blockQuoteColor(parseColor(it)) }
            builder.blockQuoteWidth(dp(widthDp))
        }

        // 链接
        with(config.link) {
            color?.let { builder.linkColor(parseColor(it)) }
            builder.isLinkUnderlined(underlined)
        }

        // 标题字号倍率
        builder.headingTextSizeMultipliers(config.heading.sizeMultipliers.toFloatArray())

        // 水平分割线
        with(config.thematicBreak) {
            color?.let { builder.thematicBreakColor(parseColor(it)) }
            builder.thematicBreakHeight(dp(heightDp))
        }
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        // 注意：addFactory 其实是 prepend（与默认工厂叠加），会把默认 CodeBlockSpan/CodeSpan
        // 一起挂上，导致直角背景盖住/超出圆角背景（右侧露出直角）。
        // 这里必须用 setFactory 真正替换默认工厂。
        // 代码块圆角：>0 时替换默认 CodeBlockSpanFactory
        if (config.code.blockCornerRadiusDp > 0f) {
            val factory = RoundedCodeBlockSpanFactory(context, config)
            builder.setFactory(FencedCodeBlock::class.java, factory)
            builder.setFactory(IndentedCodeBlock::class.java, factory)
        }
        // 行内代码圆角：>0 时替换默认 CodeSpanFactory
        if (config.code.inlineCornerRadiusDp > 0f) {
            builder.setFactory(Code::class.java, RoundedCodeSpanFactory(context, config))
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).toInt()

    private fun parseColor(hex: String): Int {
        val argb = themeSeedFromHex(hex) ?: return 0
        return argb.toInt()
    }
}
