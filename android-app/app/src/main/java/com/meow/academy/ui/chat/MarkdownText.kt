package com.meow.academy.ui.chat

/**
 * Markdown 渲染组件（Markwon 封装）。
 * 从 ChatScreen.kt 原子拆出，作为可复用组件（聊天气泡 / 知识库渲染等）。
 */

import android.text.method.LinkMovementMethod
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markdown 渲染：Markwon（标题/列表/表格/代码块/引用/链接/图片），
 * 经 AndroidView 嵌入 Compose。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                textSize = 15f
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view -> markwon.setMarkdown(view, markdown) },
    )
}
