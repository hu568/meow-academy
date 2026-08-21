package com.meow.academy.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import android.util.TypedValue
import com.meow.academy.data.settings.MarkdownConfig
import com.meow.academy.data.settings.themeSeedFromHex
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.SpanFactory
import io.noties.markwon.core.MarkwonTheme
import kotlin.math.ceil

/**
 * 行内代码圆角背景 Span（`code` 节点）。
 *
 * Markwon 默认的 [io.noties.markwon.core.spans.CodeSpan] 用 `TextPaint.bgColor`
 * 画直角矩形背景；这里改用 [ReplacementSpan] 自己测量宽度并绘制「胶囊/圆角芯片」，
 * 水平/垂直内边距与圆角半径来自 appconfig/markdown-config.js 的 `code.inline*` 配置。
 *
 * 注意：ReplacementSpan 会把整段行内代码当作一个不可拆分的单元，超长时整体换行，
 * 对常见的短行内代码（变量名、命令、路径）视觉一致性更好。
 */
class RoundedCodeSpan(
    private val theme: MarkwonTheme,
    private val cornerRadiusPx: Float,
    private val backgroundColor: Int?,
    private val horizontalPaddingPx: Float,
    private val verticalPaddingPx: Float,
) : ReplacementSpan() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val width = withCodeTextStyle(paint) {
            paint.measureText(text, start, end)
        }
        return ceil(width + horizontalPaddingPx * 2f).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        withCodeTextStyle(paint) {
            val bgColor = backgroundColor ?: theme.getCodeBackgroundColor(paint)
            val textWidth = paint.measureText(text, start, end)

            val left = x
            val right = x + textWidth + horizontalPaddingPx * 2f
            val rectTop = top + verticalPaddingPx
            val rectBottom = bottom - verticalPaddingPx
            val rectHeight = rectBottom - rectTop

            if (rectHeight > 0f && right > left) {
                val radius = cornerRadiusPx.coerceAtMost(rectHeight / 2f)
                this.paint.color = bgColor
                this.paint.style = Paint.Style.FILL
                rectF.set(left, rectTop, right, rectBottom)
                canvas.drawRoundRect(rectF, radius, radius, this.paint)
            }

            canvas.drawText(text, start, end, x + horizontalPaddingPx, y.toFloat(), paint)
        }
    }

    /** 临时套用行内代码文字样式，测量/绘制完恢复原 Paint，避免影响后续 run */
    private inline fun <T> withCodeTextStyle(paint: Paint, block: () -> T): T {
        val oldColor = paint.color
        val oldTypeface = paint.typeface
        val oldTextSize = paint.textSize
        theme.applyCodeTextStyle(paint)
        return try {
            block()
        } finally {
            paint.color = oldColor
            paint.typeface = oldTypeface
            paint.textSize = oldTextSize
        }
    }
}

/** 行内代码圆角 Span 工厂：从配置取圆角/背景/内边距（dp → px） */
class RoundedCodeSpanFactory(
    context: Context,
    config: MarkdownConfig,
) : SpanFactory {

    private val cornerRadiusPx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        config.code.inlineCornerRadiusDp,
        context.resources.displayMetrics,
    )

    private val horizontalPaddingPx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        config.code.inlinePaddingDp.left,
        context.resources.displayMetrics,
    )

    private val verticalPaddingPx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        config.code.inlinePaddingDp.top,
        context.resources.displayMetrics,
    )

    private val backgroundColor: Int? = config.code.inlineBackground
        ?.let { themeSeedFromHex(it) }
        ?.toInt()

    override fun getSpans(configuration: MarkwonConfiguration, props: RenderProps): Any =
        RoundedCodeSpan(
            theme = configuration.theme(),
            cornerRadiusPx = cornerRadiusPx,
            backgroundColor = backgroundColor,
            horizontalPaddingPx = horizontalPaddingPx,
            verticalPaddingPx = verticalPaddingPx,
        )
}
