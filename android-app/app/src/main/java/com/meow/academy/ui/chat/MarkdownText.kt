package com.meow.academy.ui.chat

/**
 * Markdown 渲染组件（Markwon 封装）。
 * 从 ChatScreen.kt 原子拆出，作为可复用组件（聊天气泡 / 知识库渲染等）。
 *
 * 流式渲染（`streaming = true`）采用**块级半增量渲染**（参考 semidown 等主流方案）：
 * - [splitStreamingBlocks] 把当前文本拆成稳定块 + 活动块；
 * - 稳定块由 [StreamingMarkdownRenderer] 渲染一次并缓存，不再随 token 重复解析；
 * - 只有正在增长的活动块每次刷新重渲染；
 * - 刷新频率仍按约 50ms 节流（约 20fps），控制活动块重渲染开销。
 * 因此表格可以边流式输出边渲染：表头 + 分隔行一到即按表格展示，后续行逐行追加，
 * 已闭合的表格/代码块/公式块不会再整篇重测导致画面抽动。
 *
 * 非流式渲染（`streaming = false`）走整篇一次性渲染，并保留 GFM 表格补空行逻辑。
 *
 * 公式与代码着色由 [buildMarkwon] 统一提供（LaTeX `$$…$$` 块、`$…$`/`$$…$$` 行内 + Prism4j 语法高亮）。
 */

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.settings.resolveMarkdownConfig
import io.noties.markwon.Markwon
import kotlinx.coroutines.delay

/** 流式渲染的刷新间隔：约 20fps，兼顾流畅度与活动块重渲染开销 */
private const val STREAMING_RENDER_INTERVAL_MS = 50L

/**
 * Markdown 渲染：Markwon（标题/列表/表格/代码着色/公式块/引用/链接/图片），
 * 经 AndroidView 嵌入 Compose。
 *
 * @param markdown 当前 Markdown 原文
 * @param streaming 是否处于流式输出中；true 时启用块级增量 + 节流刷新
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val textColor = MaterialTheme.colorScheme.onSurface
    val textColorArgb = textColor.toArgb()
    // LaTeX 公式字号与 TextView 正文 15sp 对齐；主题/文字色变化时重建 Markwon 与缓存
    val textSizePx = remember(context) {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            15f,
            context.resources.displayMetrics,
        )
    }
    // appconfig/markdown-config.js：仓库只存原始 JS 配置，这里按当前主题解析成具体值
    val app = context.applicationContext as MeowAcademyApp
    val configRaw by app.markdownConfigRepository.config.collectAsState()
    val config = remember(configRaw, isDark) { resolveMarkdownConfig(configRaw, isDark) }
    val markwon = remember(context, config, isDark, textColorArgb, textSizePx) {
        buildMarkwon(context, isDark, textSizePx, textColorArgb, config)
    }
    val renderer = remember(config, isDark, textColorArgb) { StreamingMarkdownRenderer() }

    // 流式节流：state 每次 delta 都会触发重组，但只按固定间隔把最新文本写进 TextView
    var rendered by remember { mutableStateOf(markdown) }
    val latestMarkdown by rememberUpdatedState(markdown)

    if (streaming) {
        // 常驻轮询循环：组件离开组合（生成结束/消息换行）时自动取消
        LaunchedEffect(Unit) {
            while (true) {
                rendered = latestMarkdown
                delay(STREAMING_RENDER_INTERVAL_MS)
            }
        }
    }

    // streaming=false 时直接使用最新 markdown（最终渲染，不节流、不经过 state）；
    // streaming=true 时使用节流后的 rendered
    val displayedMarkdown = if (streaming) rendered else markdown

    if (streaming) {
        val blocks = remember(displayedMarkdown) { splitStreamingBlocks(displayedMarkdown) }
        val activeTable = remember(displayedMarkdown) {
            blocks.active.takeIf { it.isNotBlank() }?.let { parseStreamingTable(it) }
        }
        if (activeTable != null) {
            // 活动块是表格：走 Compose 流式表格通道，稳定块与表格分开渲染
            Column(modifier) {
                if (blocks.stable.isNotEmpty()) {
                    MarkdownStableText(
                        stableBlocks = blocks.stable,
                        markwon = markwon,
                        renderer = renderer,
                        isDark = isDark,
                        textColorArgb = textColorArgb,
                    )
                }
                StreamingTable(table = activeTable)
            }
        } else {
            MarkdownTextView(
                markdown = displayedMarkdown,
                streaming = true,
                markwon = markwon,
                renderer = renderer,
                isDark = isDark,
                textColorArgb = textColorArgb,
                modifier = modifier,
            )
        }
    } else {
        MarkdownTextView(
            markdown = displayedMarkdown,
            streaming = false,
            markwon = markwon,
            renderer = renderer,
            isDark = isDark,
            textColorArgb = textColorArgb,
            modifier = modifier,
        )
    }
}

/** 非表格路径的 Markdown TextView：流式走块级半增量，最终走整篇渲染 */
@Composable
private fun MarkdownTextView(
    markdown: String,
    streaming: Boolean,
    markwon: Markwon,
    renderer: StreamingMarkdownRenderer,
    isDark: Boolean,
    textColorArgb: Int,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 15f
                movementMethod = LinkMovementMethod.getInstance()
                // 长按可选择/复制文本（先设 movementMethod 再设 isTextSelectable，链接仍可点击）
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextColor(textColorArgb)
            if (streaming) {
                // 块级半增量：稳定块命中缓存，只有活动块重新解析渲染
                val blocks = splitStreamingBlocks(markdown)
                val spanned = renderer.render(markwon, blocks)
                val key = "streaming:$isDark:$markdown"
                if (view.getTag(TAG_MARKDOWN_TEXT) != key) {
                    markwon.setParsedMarkdown(view, spanned)
                    view.setTag(TAG_MARKDOWN_TEXT, key)
                }
            } else {
                // 最终整篇渲染：补空行避免「正文行后直接跟表格」时 Markwon 不识别表格
                val normalized = normalizeTableBlocks(markdown)
                val key = "final:$isDark:$normalized"
                if (view.getTag(TAG_MARKDOWN_TEXT) != key) {
                    markwon.setMarkdown(view, normalized)
                    view.setTag(TAG_MARKDOWN_TEXT, key)
                }
            }
        },
    )
}

/** 流式表格上方的稳定块 TextView（只渲染已稳定块，命中 [StreamingMarkdownRenderer] 缓存） */
@Composable
private fun MarkdownStableText(
    stableBlocks: List<String>,
    markwon: Markwon,
    renderer: StreamingMarkdownRenderer,
    isDark: Boolean,
    textColorArgb: Int,
) {
    val stableKey = remember(stableBlocks) { stableBlocks.joinToString("\n\n") }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 15f
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextColor(textColorArgb)
            val spanned = renderer.renderStable(markwon, stableBlocks)
            val key = "stable:$isDark:$stableKey"
            if (view.getTag(TAG_MARKDOWN_TEXT) != key) {
                markwon.setParsedMarkdown(view, spanned)
                view.setTag(TAG_MARKDOWN_TEXT, key)
            }
        },
    )
}

/** TextView tag 缓存 key：已渲染文本 + 主题（主题变化时强制重绘） */
private const val TAG_MARKDOWN_TEXT = 0x4D44574F // "MDWO"

/**
 * GFM 表格要求表格块前有空行；Markwon 的 TablePlugin 同样遵守该规则，
 * 模型输出常见「一行文字后直接跟表格」导致表格被当成普通段落不渲染。
 * 这里在识别到「表头行 + 分隔行」时，若上一行非空则补一个空行；
 * 同时跳过围栏代码块与缩进代码块，避免改坏代码内容。
 */
private fun normalizeTableBlocks(markdown: String): String {
    val lines = markdown.split("\n").map { it.removeSuffix("\r") }
    val out = ArrayList<String>(lines.size + 8)
    var inFence = false
    var fenceChar = '`'
    var lastLineBlank = true

    fun isIndentedCode(line: String): Boolean =
        line.startsWith("    ") || line.startsWith("\t")

    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trim()

        // 围栏代码块状态机（``` 或 ~~~，长度 >= 3 视为围栏；闭合须同种围栏符）
        val fenceLen = when {
            trimmed.startsWith("```") -> trimmed.takeWhile { it == '`' }.length
            trimmed.startsWith("~~~") -> trimmed.takeWhile { it == '~' }.length
            else -> 0
        }
        val isFenceLine = fenceLen >= 3
        if (!inFence && isFenceLine) {
            inFence = true
            fenceChar = trimmed.first()
        } else if (inFence && isFenceLine && trimmed.first() == fenceChar && trimmed.drop(fenceLen).isBlank()) {
            inFence = false
        }

        val next = lines.getOrNull(i + 1)
        val isTableHeader = !inFence &&
            !isIndentedCode(line) &&
            line.contains("|") &&
            next != null &&
            !isIndentedCode(next) &&
            isTableDelimiter(next)

        if (isTableHeader && !lastLineBlank) {
            out += ""
        }
        out += line
        lastLineBlank = trimmed.isEmpty()
    }
    return out.joinToString("\n")
}
