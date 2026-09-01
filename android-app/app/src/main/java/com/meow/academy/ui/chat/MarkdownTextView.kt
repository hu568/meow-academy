package com.meow.academy.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.Spanned
import android.widget.TextView

/**
 * Markdown 段落 TextView（聊天气泡正文 / 表格单元格共用）。
 *
 * 存在的唯一理由：把当前排版结果 [Layout] 每帧注入文本里的 [RoundedCodeSpan]。
 *
 * 行内代码背景走 [android.text.style.LineBackgroundSpan] 绘制，而 AOSP 传给它的
 * `left/right` 恒为 `0 / 版心宽`——既不含列表项、引用块的缩进，也不含前缀的真实
 * 排版宽度（回调 paint 是未套样式的底笔）。要精确对齐背景矩形，只能反过来问 Layout：
 * `getPrimaryHorizontal(offset)` 才是与 `canvas.drawText` 同源的字形 x。
 *
 * 注入时机选在 [onDraw] 开头：此时 `getLayout()` 必定是本帧要用的那份，且早于
 * `Layout.drawBackground`，不会有一帧的错位；文本变化（[setText]）时顺带解绑旧 span，
 * 避免它们继续持有已废弃的 Layout。
 */
class MarkdownTextView(context: Context) : TextView(context) {

    private var cachedText: CharSequence? = null
    private var cachedSpans: Array<out RoundedCodeSpan>? = null

    override fun setText(text: CharSequence?, type: BufferType?) {
        // 旧文本的 span 即将作废，先断掉 Layout 引用防泄漏
        cachedSpans?.forEach { it.bindLayout(null) }
        cachedText = null
        cachedSpans = null
        super.setText(text, type)
    }

    override fun onDraw(canvas: Canvas) {
        bindLayoutToCodeSpans()
        super.onDraw(canvas)
    }

    private fun bindLayoutToCodeSpans() {
        val currentLayout: Layout = layout ?: return
        val current = text
        var spans = cachedSpans
        if (cachedText !== current || spans == null) {
            spans = (current as? Spanned)
                ?.getSpans(0, current.length, RoundedCodeSpan::class.java)
                ?: emptyArray()
            cachedText = current
            cachedSpans = spans
        }
        if (spans.isNotEmpty()) {
            spans.forEach { it.bindLayout(currentLayout) }
        }
    }
}
