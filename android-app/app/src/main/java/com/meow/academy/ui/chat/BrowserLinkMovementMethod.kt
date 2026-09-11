package com.meow.academy.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast

private const val TAG = "BrowserLink"

/**
 * Markdown 链接点击 → 系统浏览器。
 *
 * ⚠️ 安装顺序（M5 起的回归 bug）：必须在 `setTextIsSelectable(true)` **之后**再设置
 * `movementMethod`。TextView.setTextIsSelectable 内部会执行
 * `setMovementMethod(ArrowKeyMovementMethod.getInstance())`，先装会被静默覆盖，
 * 表现就是「链接渲染成蓝色/带下划线，但怎么点都没反应」（Markwon 的 LinkSpan 与
 * Linkify 自动识别的裸链接都受影响）。
 *
 * 点击行为：
 * - 命中 [URLSpan]（Markwon 的 LinkSpan、Linkify 的裸链接、`mailto:`/`tel:` 都是它）
 *   → `ACTION_VIEW` 交给系统挑浏览器/对应应用打开；无 scheme 的裸域名补 `https://`
 *   （与 Markwon 默认 LinkResolverDef 的兜底一致）；
 * - 其他 [ClickableSpan] → 交给 span 自身的 onClick；
 * - 没有应用能处理该链接 → Toast 提示（默认 URLSpan 只写日志，用户完全无感知）。
 */
class BrowserLinkMovementMethod : LinkMovementMethod() {

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        // ACTION_DOWN 交给父类：按下高亮与选区维护仍走 LinkMovementMethod 原逻辑
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val span = clickableSpanAt(widget, buffer, event)
            if (span != null) {
                // 这里必须直接 return：父类 ACTION_UP 分支会再触发一次 onClick，
                // 否则会同时打开两个页面（喵~）
                openSpan(widget, span)
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    /** 命中测试：与 LinkMovementMethod 内部同款算法（Layout 坐标 → 文本偏移） */
    private fun clickableSpanAt(
        widget: TextView,
        buffer: Spannable,
        event: MotionEvent,
    ): ClickableSpan? {
        val layout = widget.layout ?: return null
        val x = event.x - widget.totalPaddingLeft + widget.scrollX
        val y = event.y - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }

    private fun openSpan(widget: TextView, span: ClickableSpan) {
        val url = (span as? URLSpan)?.url
        if (url == null) {
            span.onClick(widget)
            return
        }
        val context = widget.context
        // 优先用 Activity 启动：不加 NEW_TASK 时浏览器压在本 App 任务栈上，返回键能回到喵仓；
        // 只有拿不到 Activity（Service/预览 Context）才退化为 NEW_TASK 启动。
        val activity = context.findActivity()
        val intent = Intent(Intent.ACTION_VIEW, normalize(url)).apply {
            if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            (activity ?: context).startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for link: $url", e)
            Toast.makeText(context, "没有找到能打开这个链接的应用喵~", Toast.LENGTH_SHORT).show()
        }
    }

    /** 沿 ContextWrapper 链找宿主 Activity（Compose 里 LocalContext 可能是主题包装过的 Context） */
    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** 裸域名（`example.com/x`）没有 scheme：补 `https://`，与 Markwon 默认 resolver 一致 */
    private fun normalize(url: String): Uri {
        val uri = Uri.parse(url)
        return if (uri.scheme.isNullOrEmpty()) {
            uri.buildUpon().scheme("https").build()
        } else {
            uri
        }
    }
}
