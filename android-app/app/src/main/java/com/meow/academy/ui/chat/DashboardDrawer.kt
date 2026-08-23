package com.meow.academy.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 功能看板三个功能页 */
enum class DashboardFeature(val label: String) {
    MODELS("模型管理"),
    FILES("快捷文件"),
    STATS("调用量"),
}

/**
 * 右侧「功能看板」抽屉：自绘 overlay（Material3 1.3.0 无 ModalEndDrawerSheet）。
 * 结构与左抽屉对称：scrim + 从右滑入面板（fillMaxWidth(0.85f) + widthIn(max=360.dp)）。
 * 头部：≡ + 当前功能名 + “点击下方切换” + 关闭钮；下方 3 个 FilterChip 切换功能。
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
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            // 遮罩：点击关闭（无涟漪）
            val scrimInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = onClose,
                    )
                    .background(Color.Black.copy(alpha = 0.25f)),
            )
            // 右滑面板
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .widthIn(max = 360.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(feature.label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "点击下方切换",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭功能看板")
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DashboardFeature.entries.forEach { f ->
                        FilterChip(
                            selected = f == feature,
                            onClick = { onFeatureChange(f) },
                            label = { Text(f.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
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