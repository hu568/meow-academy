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
import android.util.LruCache
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import com.meow.academy.data.settings.ChatBackground
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 进程级聊天底图内存缓存（按文件路径）。
 *
 * 组件切走时 produceState 会被销毁，若不缓存，每次切回聊天页都要重新解码大图；
 * 这里把解码结果按路径缓存住（LruCache，约 32MB），切 Tab/重进页面秒出。
 */
private val chatBgCache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        value.width * value.height * 4
}

/**
 * 聊天页底图 + 遮罩。
 *
 * @param bg 已解析的聊天底图模型（由 ChatViewModel 统一双模式解析：简单模式 DataStore / 动态模式 JSONC）
 * @param modifier 外部修饰
 */
@Composable
fun ChatBackgroundLayer(bg: ChatBackground, modifier: Modifier = Modifier) {
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
                    // 先查缓存，命中就不重新解码；miss 才走 IO 解码并写入缓存
                    value = chatBgCache.get(bg.path) ?: withContext(Dispatchers.IO) {
                        decodeChatBg(bg.path)?.also { chatBgCache.put(bg.path, it) }
                    }
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

        // 可读性遮罩：按主题用「暗纱」而不是浅色模式的白纱（白纱会把底图洗白）；
        // 浅色模式用极淡暗纱保底图原色，深色模式用稍浓暗纱托住白色文字。
        if (bg !is ChatBackground.None) {
            val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val scrimColor = if (isDarkTheme) Color.Black.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.12f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor),
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
