package com.meow.academy.ui.chat

/**
 * Markdown 渲染组件（Markwon + Compose 块级列表渲染）。
 * 从 ChatScreen.kt 原子拆出，作为可复用组件（聊天气泡 / 知识库渲染等）。
 *
 * M5 升级为「块级列表渲染」：
 * - [parseMarkdownBlocks] 把整篇文本拆成 [MdBlock]（段落 / 围栏代码 / 表格 / 数学块 / mermaid）；
 * - 普通段落由 [ParagraphBlock] 走 Markwon → TextView（保留 Spanned 缓存）；
 * - 表格 / 代码块 / 数学块 / mermaid 分别走 Compose 组件（圆角、复制按钮、横向滚动、WebView 渲染）；
 * - 流式时块列表每 tick 全量重解析（成本 ~0.7ms/2 万字，见 plan-chat-streaming-render §〇），
 *   渲染侧靠「未变块不重组」+ LazyColumn 位置复用避免重排；
 *
 * 公式与代码着色由 [buildMarkwon] 统一提供（LaTeX `$$…$$` 块、`$…$`/`$$…$$` 行内 + Prism4j 语法高亮），
 * TODO 列表（- [ ]）与删除线（~~text~~）由 Markwon 插件渲染。
 */

import android.util.TypedValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.settings.MarkdownConfig
import com.meow.academy.data.settings.resolveMarkdownConfig
import com.meow.academy.ui.theme.LocalDarkTheme
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.delay

/** 流式渲染的刷新间隔：约 20fps，兼顾流畅度与活动块重渲染开销 */
private const val STREAMING_RENDER_INTERVAL_MS = 50L

/** TextView tag 缓存 key：已渲染文本 + 主题（主题变化时强制重绘） */
private const val TAG_MARKDOWN_TEXT = 0x4D44574F // "MDWO"

/**
 * Markdown 渲染：标题/列表/表格/代码着色/公式块/引用/链接/图片/TODO/删除线/mermaid，
 * 经块级 Compose 渲染嵌入聊天气泡与知识库预览。
 *
 * @param markdown 当前 Markdown 原文
 * @param streaming 是否处于流式输出中；true 时启用块级增量 + 节流刷新
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    // 取实际应用主题的深浅（强制深色/浅色也生效），不能只看系统
    val isDark = LocalDarkTheme.current
    val textColorArgb = textColor.toArgb()
    // LaTeX 公式字号与 TextView 正文 15sp 对齐；主题/文字色变化时重建 Markwon 与缓存
    val textSizePx = remember(context) {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            15f,
            context.resources.displayMetrics,
        )
    }
    // appconfig/markdown-config.jsonc：仓库只存原始 JSONC 配置，这里按当前主题解析成具体值
    val app = context.applicationContext as MeowAcademyApp
    val configRaw by app.markdownConfigRepository.config.collectAsState()
    val config = remember(configRaw, isDark) { resolveMarkdownConfig(configRaw, isDark) }
    val markwon = remember(context, config, isDark, textColorArgb, textSizePx) {
        buildMarkwon(context, isDark, textSizePx, textColorArgb, config)
    }
    // 代码块组件复用的 Prism4j 实例与主题（与 Markwon 内部高亮一致）
    val prism4j = remember { Prism4j(MarkdownGrammarLocator()) }
    val prismTheme = remember(isDark) {
        if (isDark) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
    }

    // 流式节流：state 每次 delta 都会触发重组，但只按固定间隔把最新文本写进渲染
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
    val blocks = remember(displayedMarkdown) { parseMarkdownBlocks(displayedMarkdown) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            // key(block)：只是「声明式地说明这个块的身份」，别指望它在中途插块/过滤时保住身份——
            // Compose 的位置匹配才是身份来源（位置变了状态就重建）。而且 Compose 1.7 的
            // `key(vararg keys)` 实现就是 `block()`（keys 参数 UNUSED_PARAMETER，反编译
            // runtime-release.aar 确认调用点被内联成纯 block()）。
            // 稳定块能跳过重组，真正靠的是编译器为 lambda 捕获值生成的 changedInstance 守卫：
            // 内容不变的 data class MdBlock 传参不变 → 跳过该块的重组。
            key(block) {
                when (block) {
                    is MdBlock.Paragraph -> ParagraphBlock(
                        text = block.text,
                        markwon = markwon,
                        isDark = isDark,
                        textColorArgb = textColorArgb,
                    )

                    is MdBlock.FencedCode ->
                        if (block.language.equals("mermaid", ignoreCase = true)) {
                            // mermaid 围栏：未闭合时显示代码块样式，闭合后走 WebView 渲染
                            if (block.closed) {
                                MermaidBlock(
                                    code = block.code,
                                    isDark = isDark,
                                    config = config,
                                )
                            } else {
                                CodeBlockCompose(
                                    language = block.language,
                                    code = block.code,
                                    isDark = isDark,
                                    config = config,
                                )
                            }
                        } else {
                            CodeBlockCompose(
                                language = block.language,
                                code = block.code,
                                isDark = isDark,
                                config = config,
                            )
                        }

                    is MdBlock.Table -> MarkdownTable(
                        table = StreamingTable(block.header, block.aligns, block.rows),
                        isDark = isDark,
                        config = config,
                        markwon = markwon,
                    )

                    is MdBlock.MathBlock ->
                        if (block.closed) {
                            MathBlockCompose(
                                latex = block.latex,
                                config = config,
                                markwon = markwon,
                            )
                        } else {
                            // 未闭合公式：显示围栏原文，方便流式中看到输入进度
                            CodeBlockCompose(
                                language = null,
                                code = "$$\n${block.latex}",
                                isDark = isDark,
                                config = config,
                            )
                        }

                    is MdBlock.Mermaid ->
                        if (block.closed) {
                            MermaidBlock(
                                code = block.code,
                                isDark = isDark,
                                config = config,
                            )
                        } else {
                            CodeBlockCompose(
                                language = "mermaid",
                                code = block.code,
                                isDark = isDark,
                                config = config,
                            )
                        }

                    is MdBlock.Image -> ImageBlock(
                        alt = block.alt,
                        src = block.src,
                        config = config,
                    )
                }
            }
        }
    }
}

/**
 * 普通段落块：Markwon 渲染成 Spanned 后交给 TextView，长按可选中、链接可点开浏览器。
 *
 * 必须用 [MarkdownTextView] 而非裸 TextView：行内代码圆角背景要靠它注入的 Layout
 * 才能对齐真实排版坐标（详见该类的注释）。
 */
@Composable
private fun ParagraphBlock(
    text: String,
    markwon: Markwon,
    isDark: Boolean,
    textColorArgb: Int,
    modifier: Modifier = Modifier,
) {
    val spanned = remember(text, isDark) { markwon.toMarkdown(text) }
    // 只有「纯分割线段落」（如 ---、***、_ _ _）才撑满容器：
    // ThematicBreakSpan 画到 TextView Canvas 宽度为止，wrap-content 会让 Canvas 塌缩成几像素。
    val fillWidth = remember(text) { isThematicBreakLine(text) }
    AndroidView(
        modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
        factory = { ctx ->
            MarkdownTextView(ctx).apply {
                textSize = 15f
                // ⚠️ 顺序不可颠倒：setTextIsSelectable(true) 内部会
                // setMovementMethod(ArrowKeyMovementMethod.getInstance())，先装链接点击会被覆盖
                // → 链接显示成可点但实际点不动（M5 起的回归 bug）。
                setTextIsSelectable(true)
                movementMethod = BrowserLinkMovementMethod()
            }
        },
        update = { view ->
            view.setTextColor(textColorArgb)
            if (view.getTag(TAG_MARKDOWN_TEXT) != text) {
                markwon.setParsedMarkdown(view, spanned)
                view.setTag(TAG_MARKDOWN_TEXT, text)
            }
        },
    )
}
