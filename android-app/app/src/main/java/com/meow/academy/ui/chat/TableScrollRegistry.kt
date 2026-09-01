package com.meow.academy.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Rect

/**
 * 记录当前已组合的 MarkdownTable 横向滚动状态及其窗口可见 bounds。
 *
 * 为什么需要它（喵~）：
 * 在聊天页的长 Markdown 消息里，`LazyColumn` 对超过视口高度的 item 存在命中区域截断，
 * 表格下半部分（尤其是行数较多的宽表）虽然能画出来，但 Compose 不会把触摸事件派发给
 * 表格内层的 `horizontalScroll`/AndroidView。结果手势直接落到外层抽屉观察器 → 左右滑动
 * 触发聊天页抽屉。
 *
 * 这里的兜底方案：MarkdownTable 组合时把自己注册进来；ChatMessageList 的抽屉观察器在
 * 收到「未被内层消费」的横向拖拽时，先查注册表 —— 如果手指落在某个表格的可见范围内，
 * 就直接驱动这个表格的 ScrollState 横向滚动并消费手势，而不是打开抽屉。
 */
object TableScrollRegistry {

    private class Entry(
        val scrollState: ScrollState,
        var bounds: Rect?,
    )

    private val entries = mutableListOf<Entry>()

    /** MarkdownTable 组合时注册 */
    fun register(scrollState: ScrollState) {
        if (entries.none { it.scrollState === scrollState }) {
            entries += Entry(scrollState, null)
        }
    }

    /** MarkdownTable 离开组合时注销 */
    fun unregister(scrollState: ScrollState) {
        entries.removeAll { it.scrollState === scrollState }
    }

    /** MarkdownTable 每次布局后更新窗口 bounds（会被 LazyColumn 裁成可见区域） */
    fun updateBounds(scrollState: ScrollState, bounds: Rect) {
        entries.firstOrNull { it.scrollState === scrollState }?.bounds = bounds
    }

    /** 查找命中点（窗口坐标）下的表格；没有则返回 null */
    fun findAt(windowX: Float, windowY: Float): ScrollState? =
        entries.firstOrNull { e ->
            val b = e.bounds
            b != null && windowX >= b.left && windowX <= b.right &&
                windowY >= b.top && windowY <= b.bottom
        }?.scrollState
}
