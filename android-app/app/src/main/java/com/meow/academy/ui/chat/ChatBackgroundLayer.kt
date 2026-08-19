package com.meow.academy.ui.chat

/**
 * 聊天页底图渲染层：主题背景 / 内置渐变 / 相册自定义图片 + 可读性遮罩。
 *
 * 图片解码放到 IO 线程并降采样到 ≤2048 边长，避免大图 OOM；
 * API 28+ 用 ImageDecoder（自动处理 EXIF 方向），低版本 BitmapFactory 兜底。
 * 文件缺失/解码失败安全回退主题背景色。
 */

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.meow.academy.data.settings.ChatBackground
import com.meow.academy.data.settings.parseChatBackground
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天页底图 + 遮罩。
 *
 * @param raw 设置持久化的聊天底图字符串（"none" / "preset:<id>" / "file:<absPath>"）
 * @param modifier 外部修饰
 */
@Composable
fun ChatBackgroundLayer(raw: String, modifier: Modifier = Modifier) {
    val bg = parseChatBackground(raw)
    Box(modifier = modifier.fillMaxSize()) {
        when (bg) {
            ChatBackground.None -> {
                // 无背景：直接用主题默认背景色，不加遮罩
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }

            is ChatBackground.Preset -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                bg.preset.argbColors.map { Color(it.toInt()) },
                            ),
                        ),
                )
            }

            is ChatBackground.File -> {
                val bitmap by produceState<ImageBitmap?>(initialValue = null, bg.path) {
                    value = withContext(Dispatchers.IO) { decodeChatBg(bg.path) }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
            }
        }

        // 可读性遮罩：半透明 surface 覆盖（浅色=亮纱、深色=暗纱），气泡/文字保持清晰
        if (bg !is ChatBackground.None) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            )
        }
    }
}

/** 解码聊天底图（IO 线程调用）：降采样到 ≤2048 边长，失败返回 null */
private fun decodeChatBg(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.exists()) return null
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val maxEdge = maxOf(info.size.width, info.size.height)
            if (maxEdge > 2048) {
                val scale = 2048f / maxEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt(),
                    (info.size.height * scale).toInt(),
                )
            }
        }
    } else {
        // 低版本粗降采样，避免超大图 OOM
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
    }
    bitmap.asImageBitmap()
}.getOrNull()
