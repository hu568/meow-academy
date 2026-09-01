package com.meow.academy.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
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
open class MarkdownTextView(context: Context) : TextView(context) {

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

/**
 * 表格单元格专用 TextView：在保持 Markwon 行内 Markdown（含行内代码圆角背景）的同时，
 * 不把「未命中链接」的按下手势吞掉，让 Compose 外层 [androidx.compose.foundation.horizontalScroll]
 * 能正常接管横向滑动（喵~）。
 *
 * 背景：TextView + [android.text.method.LinkMovementMethod] 在 ACTION_DOWN 时可能把手势
 * 标记为 consumed，Compose interop 因而让外层横向滚动收不到拖拽，在聊天页就会穿透成
 * 抽屉手势（文件管理页无抽屉所以正常）。
 * 这里先判断按点是否命中 [ClickableSpan]：命中才交给父类（链接可点），
 * 未命中直接返回 false，把整条手势放行给 Compose 水平滚动处理。
 */
class MarkdownCellTextView(context: Context) : MarkdownTextView(context) {

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 非链接按点不消费：外层表格横向滚动可以接收到水平拖拽
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !isClickableLinkAt(event)) {
            return false
        }
        return super.onTouchEvent(event)
    }

    private fun isClickableLinkAt(event: MotionEvent): Boolean {
        val buffer = text as? Spanned ?: return false
        val layout = layout ?: return false
        val x = event.x - totalPaddingLeft + scrollX
        val y = event.y - totalPaddingTop + scrollY
        val line = layout.getLineForVertical(y.toInt())
        val off = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(off, off, ClickableSpan::class.java).isNotEmpty()
    }
}
