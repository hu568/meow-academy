package com.meow.academy.ui.chat

import android.text.SpannableStringBuilder
import android.text.Spanned
import io.noties.markwon.Markwon

/**
 * 流式块缓存渲染器（半增量渲染的渲染侧）。
 *
 * - 稳定块用 [Markwon.toMarkdown] 渲染一次后进 LRU 缓存，之后不再重复解析；
 * - 稳定前缀整体缓存在 SpannableStringBuilder 里，仅在 stable 列表增长时追加，
 *   避免每个 token 都 O(n) 重拼整篇 Spanned；
 * - 活动块每次调用 [render] 都重新渲染（这是「块内行内重渲染」的部分）。
 *
 * 生命周期与单条消息气泡绑定：气泡销毁/流式结束即释放，缓存不会跨消息累积。
 */
class StreamingMarkdownRenderer {

    /** 稳定块 Spanned 缓存（LRU，上限 MAX_CACHE_BLOCKS） */
    private val blockCache = object : LinkedHashMap<String, Spanned>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Spanned>?): Boolean =
            size > MAX_CACHE_BLOCKS
    }

    /** 已渲染的稳定前缀（含块间分隔符），仅在 stable 数量变化时重建 */
    private var stablePrefix: SpannableStringBuilder? = null
    private var stableCount = 0

    /** 渲染当前流式状态：稳定前缀（缓存）+ 活动块（每次重渲染） */
    fun render(markwon: Markwon, blocks: StreamingBlocks): Spanned {
        if (blocks.stable.size != stableCount) {
            stablePrefix = SpannableStringBuilder().apply {
                blocks.stable.forEach { block ->
                    append(renderedBlock(markwon, block))
                    append(SEPARATOR)
                }
            }
            stableCount = blocks.stable.size
        }

        val result = SpannableStringBuilder(stablePrefix ?: SpannableStringBuilder())
        if (blocks.active.isNotBlank()) {
            result.append(markwon.toMarkdown(blocks.active))
        }
        return result
    }

    private fun renderedBlock(markwon: Markwon, block: String): Spanned =
        blockCache.getOrPut(block) { markwon.toMarkdown(block) }

    private companion object {
        const val MAX_CACHE_BLOCKS = 256

        /** 块间连接符 = 一个空行（与 Markdown 块级语义一致） */
        const val SEPARATOR = "\n\n"
    }
}
