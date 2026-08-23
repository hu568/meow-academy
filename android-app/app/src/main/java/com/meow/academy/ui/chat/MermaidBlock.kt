package com.meow.academy.ui.chat

import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meow.academy.data.settings.MarkdownConfig

/**
 * Mermaid 围栏渲染组件（M5 Markdown 升级，喵~）。
 *
 * ` ```mermaid ` 代码块用离线 WebView + `assets/mermaid/mermaid.min.js` 渲染 SVG：
 * - 按 [MarkdownConfig.mermaid.theme] 选择 mermaid 主题，配置为空时按 [isDark] 回退到 dark/default；
 * - 渲染完成后通过 JS 回调把实际高度传回 Compose，实现高度自适应（默认完整显示整张图）；
 * - 开启双指缩放 + 放大后单指平移，默认整图完整显示；
 * - 图块背景带圆角（[MarkdownConfig.mermaid.cornerRadiusDp] / [MarkdownConfig.mermaid.blockBackground]）；
 * - gantt 用 `useWidth:720` 先按足够宽度排版再缩放，避免日期刻度被手机窄屏挤叠在一起；
 * - 语法错误时回退为可复制的原始代码块，避免白屏。
 *
 * @param code mermaid 图源码（不含 ```mermaid 围栏标记）
 * @param modifier 外层修饰符
 * @param isDark 当前是否深色主题（config.mermaid.theme 为空时的默认主题依据）
 * @param config Markdown 渲染配置（读取 mermaid.theme）
 */
@Composable
fun MermaidBlock(
    code: String,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    config: MarkdownConfig,
) {
    val context = LocalContext.current
    var webHeight by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 图块外观：圆角 + 背景（沿用动态配置体系，null 时回退主题 surfaceVariant）
    val cornerShape = RoundedCornerShape(config.mermaid.cornerRadiusDp.dp)
    val containerColor = parseColor(config.mermaid.blockBackground)
        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            // allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs 保持默认 false，
            // 页面内 JS 不能借 file:// 越权读取其他本地文件（喵~）。
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // 预览交互：默认整图完整显示，支持双指缩放 + 放大后单指平移（喵~）
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // WebView 背景透明，让 Compose 圆角容器背景透出来
            setBackgroundColor(AndroidColor.TRANSPARENT)

            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onRendered(height: Int) {
                        webHeight = height
                    }

                    @JavascriptInterface
                    fun onError(message: String) {
                        errorMessage = message
                    }
                },
                "Android",
            )
        }
    }

    // 生命周期：离开组合时销毁 WebView，避免内存泄漏
    DisposableEffect(webView) {
        onDispose {
            webView.destroy()
        }
    }

    LaunchedEffect(code, isDark, config.mermaid.theme) {
        // 重新渲染前清掉上一次结果：先回到 200dp 占位高度，避免旧高度/旧错误残留
        errorMessage = null
        webHeight = 0

        val theme = config.mermaid.theme.ifEmpty { if (isDark) "dark" else "default" }
        val escapedTheme = theme.replace("'", "\\'")
        val escapedCode = code
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html = """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
            <script src="mermaid.min.js"></script>
            <script>mermaid.initialize({startOnLoad:false, theme:'$escapedTheme', gantt:{useWidth:720, tickInterval:'2day'}});</script>
            <style>html,body{margin:0;padding:8px;background:transparent} svg{max-width:100% !important;height:auto;display:block;margin:0 auto}</style>
            </head><body><div class="mermaid">$escapedCode</div></body></html>
        """.trimIndent()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // theme 已在 <head> 的内联脚本里初始化（startOnLoad:false），这里直接 run，
                // 避免 mermaid 默认 startOnLoad=true 先用 default 主题自动渲染、run 跳过已处理节点。
                view?.evaluateJavascript(
                    "mermaid.run({nodes:[document.querySelector('.mermaid')]}).then(function(){ " +
                        "Android.onRendered(Math.round(document.body.scrollHeight * window.devicePixelRatio)); }).catch(function(e){ " +
                        "Android.onError(e.message); });",
                    null,
                )
            }
        }
        webView.loadDataWithBaseURL("file:///android_asset/mermaid/", html, "text/html", "UTF-8", null)
    }

    if (errorMessage != null) {
        // 渲染失败：显示原始代码（等宽、圆角背景、可选中），不显示 WebView
        SelectionContainer(
            modifier = modifier
                .fillMaxWidth()
                .clip(cornerShape)
                .background(containerColor)
                .padding(12.dp),
        ) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        AndroidView(
            factory = { webView },
            modifier = modifier
                .fillMaxWidth()
                .clip(cornerShape)
                .background(containerColor)
                .height(
                    if (webHeight > 0) with(LocalDensity.current) { webHeight.toDp() } else 200.dp,
                ),
            update = { view ->
                if (webHeight > 0) {
                    view.layoutParams = view.layoutParams.apply { height = webHeight }
                }
            },
        )
    }
}

/** 解析配置里的十六进制颜色；null / 非法值返回 null（调用方回退主题色） */
private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(AndroidColor.parseColor(hex)) }.getOrNull()
}
