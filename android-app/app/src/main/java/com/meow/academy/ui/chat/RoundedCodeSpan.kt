package com.meow.academy.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import com.meow.academy.data.settings.MarkdownConfig
import com.meow.academy.data.settings.themeSeedFromHex
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.SpanFactory
import io.noties.markwon.core.MarkwonTheme

/**
 * 行内代码圆角背景 Span（`code` 节点），支持自动换行。
 *
 * 旧实现用 [ReplacementSpan] 画「胶囊/圆角芯片」，但 ReplacementSpan 会把整段行内代码
 * 当作不可拆分的原子单元，超过行宽时整体溢出/截断，无法在代码内部断行。
 *
 * 这里改用两个接口的组合（同一个 span 实例同时实现两者）：
 * - [MetricAffectingSpan]：仅设置等宽字体、文字样式，本身不占布局尺寸，
 *   文字可正常参与排版断行（在空格处 / 行宽边界处断开）；
 * - [LineBackgroundSpan]：按行绘制圆角背景，每行画一个胶囊，跨行时每行各自独立圆角。
 *
 * == 水平定位必须借 Layout ==
 * AOSP `Layout.drawText` 给 [LineBackgroundSpan] 的 `left/right` 恒为 `0 / 版心宽`，
 * 源码注释原话：「LineBackgroundSpans know nothing about the alignment, margins, or
 * direction of the layout or line」。也就是说：
 * 1. 列表项 / 引用块的缩进（[LeadingMarginSpan]）**不在** `left` 里；
 * 2. 回调给的 `p` 是排版底笔（未套任何样式），拿它 `measureText` 量前缀，
 *    遇到粗体 / 等宽 / 行内公式（ReplacementSpan）时宽度全错。
 *
 * 所以背景矩形不能自己算，必须由宿主 [MarkdownTextView] 每帧注入 [Layout]，
 * 用 [Layout.getPrimaryHorizontal] 取真实排版 x（= 段落左边界 + 缩进 + 对齐偏移 +
 * 该行内到 offset 的真实排版宽度，与 canvas.drawText 画的坐标完全同源）。
 * 拿不到 Layout 时退化为「手工累加缩进 + 底笔量前缀」的近似路径，至少不再整体偏左。
 *
 * == 圆角规则 ==
 * 每行背景矩形会裁剪到行的可视边界（[left] ~ [right]），保证圆角圆弧不被 canvas 切掉。
 * 四个角按以下规则决定是否圆角：
 * - 左圆角：代码段在此行**开始**（即该行是代码段的第一行或唯一行）
 * - 右圆角：代码段在此行**结束**（即该行是代码段的最后一行或唯一行）
 * - 跨行中间行左右都直角，视觉上首行左圆、末行右圆、中间直边衔接。
 * 半径同时受矩形高**和宽**约束，避免短代码（如单个字符）两侧圆弧互相吞掉。
 */
class RoundedCodeSpan(
    private val theme: MarkwonTheme,
    private val cornerRadiusPx: Float,
    private val backgroundColor: Int?,
    private val horizontalPaddingPx: Float,
    private val verticalPaddingPx: Float,
) : MetricAffectingSpan(), LineBackgroundSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private val path = Path()

    /** 等宽样式测量笔（复用，避免每行 new Paint 抖动） */
    private val codePaint = TextPaint()

    /** 宿主 [MarkdownTextView] 每帧 onDraw 前注入；null = 走近似回退路径 */
    private var layout: Layout? = null

    // 同一份排版结果会被反复重绘（选中手柄、局部失效），缓存本次算出的矩形左右边
    private var cacheLayout: Layout? = null
    private var cacheSpanStart = -1
    private var cacheSpanEnd = -1
    private var cacheXStart = 0f
    private var cacheXEnd = 0f

    /** 由 [MarkdownTextView] 在 onDraw 前调用；换文本 / 换排版时顺带作废坐标缓存 */
    fun bindLayout(newLayout: Layout?) {
        if (layout !== newLayout) {
            cacheLayout = null
            cacheSpanStart = -1
        }
        layout = newLayout
    }

    // ── 文字样式（MetricAffectingSpan）：只设字体/字号，不画背景，文字可正常换行 ──

    override fun updateMeasureState(textPaint: TextPaint) {
        theme.applyCodeTextStyle(textPaint)
    }

    override fun updateDrawState(textPaint: TextPaint) {
        theme.applyCodeTextStyle(textPaint)
    }

    // ── 每行圆角背景（LineBackgroundSpan） ──

    override fun drawBackground(
        canvas: Canvas,
        p: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lnum: Int,
    ) {
        // 参数 start/end = 当前行的字符范围；text = 整段 Spanned
        val spanned = text as? Spanned ?: return
        val rawStart = spanned.getSpanStart(this)
        val rawEnd = spanned.getSpanEnd(this)
        if (rawStart < 0 || rawEnd < 0 || rawStart >= rawEnd) return

        // 当前行与本代码段的交集 → 本行上可见的代码片段
        val spanStart = maxOf(rawStart, start)
        val spanEnd = minOf(rawEnd, end)
        if (spanStart >= spanEnd) return

        val bgColor = backgroundColor ?: theme.getCodeBackgroundColor(p)

        // 等宽样式测量笔：与排版一致（applyCodeTextStyle 同时设字号 / 字体 / 颜色）
        codePaint.set(p)
        theme.applyCodeTextStyle(codePaint)

        // ── 本行上代码片段左右两端的真实排版 x ──
        val l = layout
        val xStart: Float
        val xEnd: Float
        if (l != null && l.text === text && lnum in 0 until l.lineCount) {
            if (cacheLayout === l && cacheSpanStart == spanStart && cacheSpanEnd == spanEnd) {
                xStart = cacheXStart
                xEnd = cacheXEnd
            } else {
                // getPrimaryHorizontal 与 drawText 同源：段落左边界（含列表/引用缩进与对齐
                // 偏移）+ 本行行首到该字符的真实排版宽度，前缀有粗体 / 公式都不影响。
                xStart = l.getPrimaryHorizontal(spanStart)
                // 行尾那个位置（\n 或文本末尾）会被 Layout 判给下一行，只能自己量。
                val lineEnd = l.getLineEnd(lnum)
                val measuredEnd =
                    if (spanEnd < lineEnd) l.getPrimaryHorizontal(spanEnd)
                    else xStart + codePaint.measureText(text, spanStart, spanEnd)
                cacheLayout = l
                cacheSpanStart = spanStart
                cacheSpanEnd = spanEnd
                cacheXStart = xStart
                cacheXEnd = measuredEnd
                xEnd = measuredEnd
            }
        } else {
            // 回退（宿主不是 MarkdownTextView）：至少把缩进补上，前缀仍按底笔近似测量
            xStart = left + fallbackLeadingMargin(spanned, start, end) +
                p.measureText(text, start, spanStart)
            xEnd = xStart + codePaint.measureText(text, spanStart, spanEnd)
        }

        // 理想矩形（未裁剪）：背景覆盖文字 + 左右 padding
        val idealLeft = xStart - horizontalPaddingPx
        val idealRight = xEnd + horizontalPaddingPx

        // 裁剪到行的可视边界 [left, right]。
        // 注意：drawRoundRect 的圆角圆弧在矩形内部凹进去，只要矩形不越界，
        // 圆角就完整可见；越界部分（如行首代码 rectLeft 想伸到 left 左边）
        // 会被 clamp 掉，同时保留圆角——视觉上背景紧贴行边界但圆角完整。
        val clampedLeft = maxOf(idealLeft, left.toFloat())
        val clampedRight = minOf(idealRight, right.toFloat())

        val rectTop = top + verticalPaddingPx
        val rectBottom = bottom - verticalPaddingPx
        val rectHeight = rectBottom - rectTop
        val rectWidth = clampedRight - clampedLeft

        if (rectHeight > 0f && rectWidth > 0f) {
            // 半径同时受高宽约束：短代码段（如 `a`）两侧圆弧不能互相吞掉
            val radius = cornerRadiusPx
                .coerceAtMost(rectHeight / 2f)
                .coerceAtMost(rectWidth / 2f)

            // ── 判断四个角是否需要圆角 ──
            // 左圆角：代码段在此行开始（第一行或唯一行）
            val leftRounded = spanStart == rawStart
            // 右圆角：代码段在此行结束（最后一行或唯一行）
            val rightRounded = spanEnd == rawEnd

            val tl = if (leftRounded) radius else 0f
            val tr = if (rightRounded) radius else 0f
            val br = if (rightRounded) radius else 0f
            val bl = if (leftRounded) radius else 0f

            this.paint.color = bgColor
            this.paint.style = Paint.Style.FILL
            // 用 Path.addRoundRect 实现每个角独立圆角半径
            // radii 顺序：topLeftX, topLeftY, topRightX, topRightY, bottomRightX, bottomRightY, bottomLeftX, bottomLeftY
            path.rewind()
            rectF.set(clampedLeft, rectTop, clampedRight, rectBottom)
            path.addRoundRect(rectF, floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
            canvas.drawPath(path, this.paint)
        }
    }

    /**
     * 近似回退用的行缩进：把本行覆盖到的所有 [LeadingMarginSpan] 的缩进累加。
     * 与 Layout 的真实算法（含 LeadingMarginSpan2 行数、对齐补偿）会有出入，
     * 只在拿不到 Layout 时兜底，正常路径不走这里。
     */
    private fun fallbackLeadingMargin(spanned: Spanned, lineStart: Int, lineEnd: Int): Int {
        var margin = 0
        val spans = spanned.getSpans(lineStart, lineEnd, LeadingMarginSpan::class.java)
        for (span in spans) {
            margin += span.getLeadingMargin(lineStart <= spanned.getSpanStart(span))
        }
        return margin
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
