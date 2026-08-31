package com.meow.academy.ui.chat

/**
 * 聊天页右侧功能看板 overlay 分片（plan-chatscreen-refactor §2.3）：
 * DashboardDrawer 4 slot 挂载，放在 ModalNavigationDrawer 外层盖住聊天内容。
 * 同样只垫内容区（bottomPadding），底图层仍保持全屏。
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 右侧功能看板 overlay：薄壳经 4 个 slot 传入各面板（需要读薄壳收集的 provider/model/统计等状态）。
 */
@Composable
fun ChatDashboardOverlay(
    modifier: Modifier = Modifier,
    open: Boolean,
    feature: DashboardFeature,
    bottomPadding: Dp,
    onFeatureChange: (DashboardFeature) -> Unit,
    onClose: () -> Unit,
    modelPanel: @Composable () -> Unit,
    filesPanel: @Composable () -> Unit,
    statsPanel: @Composable () -> Unit,
    workspaceSettingsPanel: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
    ) {
        DashboardDrawer(
            open = open,
            feature = feature,
            onFeatureChange = onFeatureChange,
            onClose = onClose,
            modelPanel = modelPanel,
            filesPanel = filesPanel,
            statsPanel = statsPanel,
            workspaceSettingsPanel = workspaceSettingsPanel,
        )
    }
}
