package com.meow.academy.ui.chat

/**
 * Markdown 渲染组件（Markwon 封装）。
 * 从 ChatScreen.kt 原子拆出，作为可复用组件（聊天气泡 / 知识库渲染等）。
 *
 * 支持流式渲染：`streaming = true` 时不会每个 token 都触发一次完整 Markdown 解析，
 * 而是把最新文本以约 50ms 的固定间隔节流推进到 TextView（参考 FluidMarkdown /
 * 社区 Android LLM 流式渲染思路），避免流式期间卡顿；`streaming = false` 时直接全量渲染。
 */

import android.text.method.LinkMovementMethod
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.delay

/** 流式渲染的刷新间隔：约 20fps，兼顾流畅度与 Markwon 解析开销 */
private const val STREAMING_RENDER_INTERVAL_MS = 50L

/**
 * Markdown 渲染：Markwon（标题/列表/表格/代码块/引用/链接/图片），
 * 经 AndroidView 嵌入 Compose。
 *
 * @param markdown 当前 Markdown 原文
 * @param streaming 是否处于流式输出中；true 时启用节流刷新
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

    // 流式节流：state 每次 delta 都会触发重组，但只按固定间隔把最新文本写进 TextView。
    var rendered by remember { mutableStateOf(markdown) }
    val latestMarkdown by rememberUpdatedState(markdown)

    if (streaming) {
        // 常驻轮询循环：组件离开组合（生成结束/消息换行）时自动取消。
        LaunchedEffect(Unit) {
            while (true) {
                rendered = latestMarkdown
                delay(STREAMING_RENDER_INTERVAL_MS)
            }
        }
    }

    // streaming=false 时直接使用最新 markdown（完成后的最终渲染，不节流、不经过 state）；
    // streaming=true 时使用节流后的 rendered，避免每个 token 都触发完整 Markdown 解析。
    val displayedMarkdown = if (streaming) rendered else markdown

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                textSize = 15f
                movementMethod = LinkMovementMethod.getInstance()
                // 长按可选择/复制文本（先设 movementMethod 再设 isTextSelectable，链接仍可点击）
                setTextIsSelectable(true)
            }
        },
        update = { view -> markwon.setMarkdown(view, displayedMarkdown) },
    )
}
