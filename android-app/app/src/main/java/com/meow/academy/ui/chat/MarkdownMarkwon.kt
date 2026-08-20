package com.meow.academy.ui.chat

import android.content.Context
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import io.noties.prism4j.annotations.PrismBundle
import org.commonmark.node.Node

/**
 * Markwon 实例构建（流式与最终渲染共用同一套全插件配置）。
 *
 * 插件栈：
 * - TablePlugin：GFM 表格；
 * - LinkifyPlugin：链接可点；
 * - SyntaxHighlightPlugin + Prism4j：代码围栏语法着色；
 * - MarkwonInlineParserPlugin：行内解析（行内 LaTeX 必需）；
 * - JLatexMathPlugin：`$$…$$` 块公式 + `$$…$$` 行内公式（jlatexmath-android 渲染成图）；
 * - DollarMathInlinePlugin：额外支持 LLM 常见输出 `$…$` 单美元行内公式。
 */

/** Prism4j 语法包声明：kapt 依此生成 com.meow.academy.ui.chat.MarkdownGrammarLocator */
@PrismBundle(
    include = [
        "clike", "c", "cpp", "csharp", "java", "kotlin", "scala",
        "python", "javascript", "json", "yaml", "markup", "css",
        "sql", "go", "dart", "swift", "makefile",
    ],
    grammarLocatorClassName = ".MarkdownGrammarLocator",
)
internal object MarkdownPrismBundle

/**
 * @param textSizePx LaTeX 公式字号（px）；与 TextView 正文 15sp 对应
 * @param textColorArgb LaTeX 公式文字色；跟随当前主题，避免深色模式下黑字不可见
 */
fun buildMarkwon(
    context: Context,
    isDark: Boolean,
    textSizePx: Float,
    textColorArgb: Int,
): Markwon {
    val prismTheme: Prism4jTheme =
        if (isDark) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
    val prism4j = Prism4j(MarkdownGrammarLocator())

    return Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(SyntaxHighlightPlugin.create(prism4j, prismTheme))
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(
            JLatexMathPlugin.create(
                textSizePx,
                JLatexMathPlugin.BuilderConfigure { builder ->
                    builder.inlinesEnabled(true)
                    // 公式解析失败（含流式未闭合）回退显示原始 LaTeX 文本
                    builder.errorHandler { _, _ -> null }
                    builder.theme().textColor(textColorArgb)
                },
            ),
        )
        // 单 $ 行内公式：Markwon 内置只认 $$…$$，LLM 主流输出是 $…$，
        // 这里补一个行内处理器，复用 JLatexMathNode（渲染侧已注册 visitor）。
        // 必须注册在 JLatexMathPlugin 之后：内建 $$ 处理器优先匹配，避免误拆 $$…$$。
        .usePlugin(DollarMathInlinePlugin())
        .build()
}

/**
 * 单 `$…$` 行内公式处理器。
 *
 * 匹配规则偏保守，避免误伤货币等普通文本：
 * - 开/闭 $ 都不能与相邻 $ 相连（`$$…$$` 交给内建处理器）；
 * - $ 后、$ 前都不能是空白（`$ x$` 不匹配）；
 * - 内容里不含 $ 与换行；
 * - 内容至少一个非空白字符（`$x$` 可匹配，`$$` 空内容不匹配）。
 * 因此 `$5 to $10`（闭 $ 前是空格）不会被当成公式。
 *
 * 注意：不使用 [match]（其内部是 find() 会向后搜索，可能跳过普通文本），
 * 而是严格从当前 index 手工匹配，避免吞掉前缀文本。
 */
private class DollarMathInlineProcessor : InlineProcessor() {

    override fun specialCharacter(): Char = '$'

    override fun parse(): Node? {
        val latex = matchDollarMath(input, index) ?: return null
        // 前进到闭 $ 之后：内容 + 两侧各一个 $
        index += latex.length + 2

        val node = JLatexMathNode()
        node.latex(latex)
        return node
    }
}

/** 把 [DollarMathInlineProcessor] 挂到 MarkwonInlineParserPlugin 上 */
private class DollarMathInlinePlugin : AbstractMarkwonPlugin() {

    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(MarkwonInlineParserPlugin::class.java)
            .factoryBuilder()
            .addInlineProcessor(DollarMathInlineProcessor())
    }
}
