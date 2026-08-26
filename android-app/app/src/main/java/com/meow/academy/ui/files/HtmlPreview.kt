package com.meow.academy.ui.files

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlin.math.roundToInt

/** HTML 预览滚动恢复：contentHeight 尚未就绪时的最大重试次数（30次 × 100ms = 3s） */
private const val SCROLL_RESTORE_MAX_ATTEMPTS = 30
/** HTML 预览滚动恢复：重试间隔（毫秒） */
private const val SCROLL_RESTORE_RETRY_MS = 100L

/**
 * HTML 文件预览组件（WebView 封装，喵~）。
 *
 * 两种加载模式：
 * - [content] 为 null：走 `loadUrl(file://…)` 直接渲染磁盘文件，编码探测与相对路径
 *   子资源（css/js/img）由 WebView 原生处理，用于首次进入预览（不读内存，大文件也稳）；
 * - [content] 非 null：走 `loadDataWithBaseURL` 渲染内存内容，baseUrl 指向文件所在目录，
 *   用于编辑器「预览」模式（编辑中未保存的内容也能看到效果）。
 *
 * 安全：开启 JS（本地页面常需要），但保持 `allowFileAccessFromFileURLs` /
 * `allowUniversalAccessFromFileURLs` 为 false，防止页面内 JS 越权读取其他本地文件。
 *
 * @param file 目标 HTML 文件（[content] 为 null 时直接加载它）
 * @param modifier 外层修饰符
 * @param content 非 null 时按内存内容渲染（忽略磁盘文件内容）
 * @param reloadKey 变化时重新加载页面（如保存后刷新）
 * @param initialScrollFraction 页面加载完成后恢复的滚动比例（0~1，相对 contentHeight）；用于模式切换回来时保持阅读位置
 * @param onScrollFractionChanged 预览滚动时实时上报当前滚动比例（scrollY / contentHeight）
 */
@Suppress("DEPRECATION") // allowFileAccessFromFileURLs / LocalLifecycleOwner 在新版本有替代 API，但当前仍是稳定可用路径
@Composable
fun HtmlWebView(
    file: File,
    modifier: Modifier = Modifier,
    content: String? = null,
    reloadKey: Int = 0,
    initialScrollFraction: Float = 0f,
    onScrollFractionChanged: (Float) -> Unit = {},
) {
    // 每个文件一个独立 WebView：换文件时整组销毁重建，避免 AndroidView 复用旧实例（喵~）
    key(file.path) {
        val context = LocalContext.current
        var progress by remember { mutableIntStateOf(100) }
        // 闭包（onPageFinished）里读最新恢复比例：滚动中的 recompose 不会触发重新加载，但恢复目标始终取最新值（喵~）
        val currentInitialScrollFraction by rememberUpdatedState(initialScrollFraction)

        val webView = remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                // 保持默认关闭：本地页面 JS 不能跨 file:// 读别的文件（喵~）
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress = newProgress
                    }
                }
                // 预览滚动时按比例上报：上层在切换模式时用它做跨模式位置锚点（喵~）
                setOnScrollChangeListener { _, _, _, _, _ ->
                    val contentHeight = this.contentHeight
                    if (contentHeight > 0) {
                        onScrollFractionChanged(scrollY.toFloat() / contentHeight)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // 只提示主页面加载失败；子资源（图片/css 等）失败不打扰
                        if (request?.isForMainFrame == true) {
                            Toast.makeText(context, "加载失败", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // 页面加载完成后恢复上次滚动位置；onPageFinished 时 contentHeight
                        // 可能仍为 0（页面布局未完成），轮询重试直到就绪（喵~）
                        val fraction = currentInitialScrollFraction
                        if (view == null || fraction <= 0f) return
                        var attempts = 0
                        val restore = object : Runnable {
                            override fun run() {
                                // contentHeight 访问用 runCatching 兜底：重试期间用户切走
                                // tab 导致 WebView 被销毁时返回 0 → attempts 耗尽后自然停止，
                                // 不会崩溃（喵~）
                                val contentHeight =
                                    runCatching { view.contentHeight }.getOrDefault(0)
                                if (contentHeight > 0) {
                                    runCatching {
                                        view.scrollTo(
                                            0,
                                            (fraction * contentHeight).roundToInt(),
                                        )
                                    }
                                } else if (attempts++ < SCROLL_RESTORE_MAX_ATTEMPTS) {
                                    runCatching { view.postDelayed(this, SCROLL_RESTORE_RETRY_MS) }
                                }
                            }
                        }
                        view.post(restore)
                    }
                }
            }
        }

        // 生命周期：随页面停/启暂停/恢复渲染，离开组合时移除观察者并销毁 WebView
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, webView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> webView.onResume()
                    Lifecycle.Event.ON_STOP -> webView.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                webView.destroy()
            }
        }

        LaunchedEffect(file.path, content, reloadKey) {
            progress = 0
            if (content != null) {
                val baseUrl = file.parentFile?.toURI()?.toString() ?: "file:///"
                webView.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null)
            } else {
                webView.loadUrl(Uri.fromFile(file).toString())
            }
        }

        Column(modifier = modifier) {
            if (progress in 1 until 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
