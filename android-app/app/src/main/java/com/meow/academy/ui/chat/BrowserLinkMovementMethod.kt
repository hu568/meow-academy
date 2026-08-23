package com.meow.academy.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast

/**
 * Markdown 链接点击 → 系统浏览器的 MovementMethod。
 *
 * 默认的 [LinkMovementMethod] 虽然能把 URLSpan 渲染成可点击链接，但在本组件
 * 当前配置下点击没有实际行为；这里在 ACTION_UP 时命中 URLSpan 后，
 * 用 [Intent.ACTION_VIEW] 调起系统浏览器打开链接，替代默认无行为的点击。
 */
class BrowserLinkMovementMethod(
    private val context: Context,
) : LinkMovementMethod() {

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x - widget.totalPaddingLeft + widget.scrollX
            val y = event.y - widget.totalPaddingTop + widget.scrollY
            val layout = widget.layout ?: return super.onTouchEvent(widget, buffer, event)
            val line = layout.getLineForVertical(y.toInt())
            val off = layout.getOffsetForHorizontal(line, x)
            val links = buffer.getSpans(off, off, URLSpan::class.java)
            if (links.isNotEmpty()) {
                val url = links.first().url
                return try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    Toast.makeText(context, "无法打开链接喵~", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}
