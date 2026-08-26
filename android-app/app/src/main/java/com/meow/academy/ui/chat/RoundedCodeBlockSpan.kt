package com.meow.academy.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import com.meow.academy.data.settings.MarkdownConfig
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.SpanFactory
import io.noties.markwon.core.MarkwonTheme

/**
 * 圆角代码块背景 Span（整块一个圆角矩形，不是每行独立圆角）。
 *
 * 默认 Markwon 的 [io.noties.markwon.core.spans.CodeBlockSpan] 用直角矩形每行画背景；
 * 这里复制它的行为（整行铺满 + 代码块文字样式），但只在第一行画一次圆角矩形，
 * 圆角半径来自 appconfig/markdown-config.jsonc 的 `code.blockCornerRadiusDp`。
 *
 * 原理：利用 [Layout] 的 [getLineForOffset] 找出当前 span 覆盖的起始/终止行，
 * 计算整块总高度，仅在第一行 [drawLeadingMargin] 时绘制整块圆角背景。
 */
class RoundedCodeBlockSpan(
    private val theme: MarkwonTheme,
    private val cornerRadiusPx: Float,
) : MetricAffectingSpan(), LeadingMarginSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    override fun updateMeasureState(textPaint: TextPaint) {
        theme.applyCodeBlockTextStyle(textPaint)
    }

    override fun updateDrawState(textPaint: TextPaint) {
        theme.applyCodeBlockTextStyle(textPaint)
    }

    override fun getLeadingMargin(first: Boolean): Int = theme.codeBlockMargin

    override fun drawLeadingMargin(
        canvas: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        // drawLeadingMargin 的 start/end 是「当前行」的字符区间，不是 span 本身的区间；
        // 必须从 Spanned 里取当前 span 的真实覆盖范围，否则多行代码块只会画出第一行的高度。
        val spanned = text as? Spanned
        val spanStart = spanned?.getSpanStart(this)?.coerceAtLeast(0) ?: start
        val spanEnd = spanned?.getSpanEnd(this)?.coerceAtLeast(spanStart) ?: end

        // 只在 span 真正开始的那一行绘制整块圆角背景，后续行跳过（避免叠加重影）。
        // 注意不能依赖 first 参数：代码块每一行都以 \n 结尾，Android Layout 会把每一行
        // 都当作独立 paragraph，first 对每一行都是 true；这里用 span 起点所在行判断才准确。
        val firstLine = layout.getLineForOffset(spanStart)
        val currentLine = layout.getLineForOffset(start)
        if (currentLine != firstLine) return

        // 计算整块的总上下边界（利用 Layout 找 span 覆盖的首/末行）
        val lastLine = layout.getLineForOffset((spanEnd - 1).coerceAtLeast(spanStart))
        val blockTop = layout.getLineTop(firstLine).toFloat()
        val blockBottom = layout.getLineBottom(lastLine).toFloat()

        // 背景铺满整个代码块文本区（layout.width），而不是 canvas.width：
        // canvas.width 是整个 View 宽度（含 padding），画到那里右边缘会被 View 裁剪，
        // 导致右侧圆角被切平、只剩左侧有圆角。两侧各收 0.5px，避免边缘恰好贴住裁剪边界。
        val textAreaWidth = layout.width.toFloat()
        val left: Float
        val right: Float
        if (dir > 0) {
            left = x + 0.5f
            right = textAreaWidth - 0.5f
        } else {
            left = (x - textAreaWidth) + 0.5f
            right = x - 0.5f
        }

        paint.style = Paint.Style.FILL
        paint.color = theme.getCodeBlockBackgroundColor(p)

        // 圆角半径不超过块高的一半，防止短块（单行）圆角重叠
        val blockHeight = blockBottom - blockTop
        val radius = cornerRadiusPx.coerceAtMost(blockHeight / 2f)

        rectF.set(left, blockTop, right, blockBottom)
        canvas.drawRoundRect(rectF, radius, radius, paint)
    }
}

/** 圆角代码块 Span 工厂：从配置取圆角半径（dp → px） */
class RoundedCodeBlockSpanFactory(
    context: Context,
    config: MarkdownConfig,
) : SpanFactory {

    private val cornerRadiusPx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        config.code.blockCornerRadiusDp,
        context.resources.displayMetrics,
    )

    override fun getSpans(configuration: MarkwonConfiguration, props: RenderProps): Any =
        RoundedCodeBlockSpan(configuration.theme(), cornerRadiusPx)
}
