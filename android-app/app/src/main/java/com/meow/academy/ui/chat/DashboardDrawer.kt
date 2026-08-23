package com.meow.academy.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 功能看板三个功能页 */
enum class DashboardFeature(val label: String) {
    MODELS("模型管理"),
    FILES("快捷文件"),
    STATS("调用量"),
}

/**
 * 右侧「功能看板」抽屉：自绘 overlay（Material3 1.3.0 无 ModalEndDrawerSheet）。
 *
 * 交互与左抽屉对齐：
 *  - 无黑色遮罩（透明点击区，点外部关闭）；
 *  - 头部：X 在左、功能名、⋮ 在右；
 *  - ⋮ 是按钮，点击弹出模式切换菜单（模型管理 / 快捷文件 / 调用量）；
 *  - 打开：顶栏「功能看板」按钮，或聊天内容区任意位置向左滑（手势在 ChatScreen 挂载）。
 */
@Composable
fun DashboardDrawer(
    open: Boolean,
    feature: DashboardFeature,
    onFeatureChange: (DashboardFeature) -> Unit,
    onClose: () -> Unit,
    modelPanel: @Composable () -> Unit,
    filesPanel: @Composable () -> Unit,
    statsPanel: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 透明遮罩：没有黑色盖层，但点击面板外区域仍可关闭
                val scrimInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            onClick = onClose,
                        ),
                )
                // 右滑面板：与左抽屉视觉一致（半透明 + 内侧圆角）
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.85f)
                        .widthIn(max = 340.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
                        // 标题与聊天页 TopAppBar 对齐：内容避开状态栏与导航栏
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        // 反向滑动收回：在面板上向右滑，和左抽屉一样可以把面板收回去
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    if (dragAmount > 0) {
                                        change.consume()
                                        onClose()
                                    }
                                },
                            )
                        },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // X 在左：关闭
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭功能看板")
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(feature.label, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "点击右侧 ⋮ 切换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // ⋮ 在右：展开模式切换菜单
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "切换功能")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DashboardFeature.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (f == feature) "${f.label}  ✓" else f.label)
                                        },
                                        onClick = {
                                            onFeatureChange(f)
                                            menuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.fillMaxSize()) {
                        when (feature) {
                            DashboardFeature.MODELS -> modelPanel()
                            DashboardFeature.FILES -> filesPanel()
                            DashboardFeature.STATS -> statsPanel()
                        }
                    }
                }
            }
        }
    }
}