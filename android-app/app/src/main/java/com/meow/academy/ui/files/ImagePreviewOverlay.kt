package com.meow.academy.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage

/**
 * 图片浮窗预览（喵~）。
 *
 * 以 Dialog 浮窗形式展示图片，不进入独立界面：
 * - 单指拖动图片位置；
 * - 双指捏合放大/缩小（1x ~ 6x）；
 * - 双指旋转图片；
 * - 右上角 90° 旋转按钮 + 关闭按钮。
 *
 * [model] 设计成通用图片源：本地可传 `File`，网络/未来聊天可传 URL `String`、`Uri`、`HttpUrl` 等，
 * 由 Coil 统一加载（喵~）。
 *
 * @param model Coil 支持的任意图片源（File / String URL / Uri 等）
 * @param displayName 预览标题（本地文件传文件名；网络图可为 null）
 * @param onDismiss 关闭回调
 */
@Composable
fun ImagePreviewOverlay(
    model: Any?,
    displayName: String?,
    onDismiss: () -> Unit,
) {
    // 缩放/平移/旋转状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    // 视口尺寸：双指缩放时以手势中心为锚点，避免图片缩放时跳开（喵~）
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
        ) {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        viewportWidth = size.width
                        viewportHeight = size.height
                    }
                    // 关键：pointerInput 必须放在 graphicsLayer 外层，
                    // 这样手势坐标是屏幕坐标，放大后拖动 1:1、旋转后也按屏幕方向移动（喵~）
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, gestureRotation ->
                            val oldScale = scale
                            val newScale = (oldScale * zoom).coerceIn(1f, 6f)
                            val factor = newScale / oldScale
                            // 缩放锚点：让手势中心对应的图片位置尽量保持不动（喵~）
                            offsetX = offsetX - (centroid.x - viewportWidth / 2f) * (factor - 1f)
                            offsetY = offsetY - (centroid.y - viewportHeight / 2f) * (factor - 1f)
                            // 单指拖动：pan 是屏幕坐标位移，直接加到屏幕空间 offset
                            offsetX += pan.x
                            offsetY += pan.y
                            // 双指旋转：gestureRotation 是本次角度增量
                            rotation = (rotation + gestureRotation) % 360f
                            scale = newScale
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                        rotationZ = rotation
                    },
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                },
                error = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "无法预览（格式不支持或加载失败）",
                            color = Color.White,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
            )

            // 顶部标题
            if (displayName != null) {
                Text(
                    text = displayName,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                )
            }

            // 右上角：90° 旋转按钮 + 关闭按钮
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                IconButton(
                    onClick = { rotation = (rotation + 90f) % 360f },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "旋转 90°",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭预览",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}