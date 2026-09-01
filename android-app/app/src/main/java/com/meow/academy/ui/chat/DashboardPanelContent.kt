package com.meow.academy.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 右侧面板内容：头部（X 在左、功能名在右）+ 分割线 + Crossfade 内容区。
 * 无状态、全部参数化注入；4 个 panel 槽位由薄壳传入。
 *
 * @param feature 当前功能页（驱动头部标题 + Crossfade 目标）
 * @param onClose 关闭回调（由薄壳的统一锚定动画提供）
 * @param modelPanel / filesPanel / statsPanel / workspaceSettingsPanel 四个功能面板槽位
 * @param modifier 由薄壳传入（融合表面已按 [FusedPanelShape] 剪裁 + 背景）
 */
@Composable
internal fun DashboardPanelContent(
    feature: DashboardFeature,
    onClose: () -> Unit,
    modelPanel: @Composable () -> Unit,
    filesPanel: @Composable () -> Unit,
    statsPanel: @Composable () -> Unit,
    workspaceSettingsPanel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = CONTENT_START_PADDING),
    ) {
        // 头部：X 在左、功能名（抽屉标题）在右
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭功能看板")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                feature.label,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        // 内容区：切换时淡入淡出
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(
                targetState = feature,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "dashboardPanel",
            ) { f ->
                when (f) {
                    DashboardFeature.MODELS -> modelPanel()
                    DashboardFeature.FILES -> filesPanel()
                    DashboardFeature.STATS -> statsPanel()
                    DashboardFeature.WORKSPACE_SETTINGS -> workspaceSettingsPanel()
                }
            }
        }
    }
}
