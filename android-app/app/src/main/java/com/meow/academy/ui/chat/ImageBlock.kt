package com.meow.academy.ui.chat

/**
 * 图片块渲染组件（M5.5：md 渲染 + 聊天图片）。
 *
 * 渲染独立成段的 `![alt](src)` Markdown 图片：
 * - 圆角 + 线框（配置驱动，视觉统一）；
 * - 加载中显示占位指示器；
 * - 加载失败显示错误文案；
 * - 点击打开 [ImagePreviewOverlay] 全屏预览（缩放/旋转/拖动）。
 */

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.meow.academy.data.settings.MarkdownConfig
import com.meow.academy.data.settings.themeSeedFromHex
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.ui.files.ImagePreviewOverlay
import java.io.File

/**
 * 渲染单张独立图片，带圆角线框，点击可预览。
 *
 * @param alt 图片 alt 文本
 * @param src 图片源路径/URL
 * @param config 渲染配置（圆角 / 线框 / 最大高度 / 加载占位色）
 * @param modifier 外层修饰符
 */
@Composable
fun ImageBlock(
    alt: String,
    src: String,
    config: MarkdownConfig,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var previewOpen by remember { mutableStateOf(false) }

    // 图片源：Coil 支持的模型（File / String URL / Uri）
    val model = remember(src, context) { resolveImageModel(context, src) }

    // 圆角 + 线框外观
    val shape = RoundedCornerShape(config.image.cornerRadiusDp.dp)
    val borderColor = config.image.borderColor?.let { parseHexColor(it) }
        ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val borderWidth = if (config.image.borderWidthDp > 0f) config.image.borderWidthDp.dp else 0.dp
    val bg = config.image.loadingBackground?.let { parseHexColor(it) }
        ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .clickable { previewOpen = true },
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = config.image.maxHeightDp.dp),
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = config.image.errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            },
        )
    }

    // 全屏预览浮窗
    if (previewOpen) {
        ImagePreviewOverlay(
            model = model,
            displayName = alt.ifBlank { null },
            onDismiss = { previewOpen = false },
        )
    }
}

/**
 * 解析图片源路径为 Coil 可加载的模型（File / String / Uri）。
 *
 * 规则：
 * - `http://` / `https://` → 直接字符串 URL（Coil 网络加载）；
 * - `file://` → 解析为 Uri（本地文件）；
 * - `content://` → 解析为 Uri（ContentProvider）；
 * - 绝对路径 → File（Coil 本地文件加载）；
 * - 相对路径 → 相对于 workspace 目录（DSH_CWD）解析为 File。
 */
private fun resolveImageModel(context: Context, src: String): Any {
    val trimmed = src.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("file://")) return Uri.parse(trimmed)
    if (trimmed.startsWith("content://")) return Uri.parse(trimmed)
    val file = File(trimmed)
    if (file.isAbsolute) return file
    // 相对路径：相对于 DSH 工作区目录
    val workspace = RuntimeExtractor.workspaceDir(context)
    return File(workspace, trimmed)
}

/** 将 #RRGGBB / #AARRGGBB HEX 字符串转为 Compose Color；非法输入返回 null */
private fun parseHexColor(hex: String): Color? {
    val argb = themeSeedFromHex(hex) ?: return null
    return Color(argb.toInt())
}