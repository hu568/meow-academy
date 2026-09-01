package com.meow.academy.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * 左侧竖向模式页签列（无状态，全部参数化注入）。
 *
 * @param selectedFeature 当前选中页签
 * @param onFeatureClick 页签点击回调（真正切换时才振动，点当前页签不重复振）
 * @param onReportBounds 选中页签 window bounds 上报（供薄壳凸起融合定位）
 * @param modifier 由薄壳传入布局对齐（[Alignment.TopStart]）
 */
@Composable
internal fun DashboardTabRail(
    selectedFeature: DashboardFeature,
    onFeatureClick: (DashboardFeature) -> Unit,
    onReportBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val onTabClick: (DashboardFeature) -> Unit = { f ->
        // 真正切换时才振动（与底部导航一致），点当前页签不重复振
        if (f != selectedFeature) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onFeatureClick(f)
    }
    Column(
        modifier = modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .padding(top = RAIL_TOP_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TAB_SPACING),
    ) {
        DashboardFeature.entries.forEach { f ->
            FeatureTab(
                feature = f,
                selected = f == selectedFeature,
                reportBounds = if (f == selectedFeature) onReportBounds else null,
                onClick = { onTabClick(f) },
            )
        }
    }
}

/**
 * 单个模式页签：
 * - 未选中：半透明（~40%）平面圆角方块 + 描边图标；
 * - 选中：透明无背景（由面板凸起供底），填充图标（标题已在头部显示，不再重复文字）；
 * - 选中时把自身 window bounds 上报给外部，用于凸起融合定位。
 */
@Composable
private fun FeatureTab(
    feature: DashboardFeature,
    selected: Boolean,
    reportBounds: ((Rect) -> Unit)?,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(TAB_SQUARE)
            .then(
                if (reportBounds != null) {
                    Modifier.onGloballyPositioned { reportBounds(it.boundsInWindow()) }
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(TAB_CORNER))
            .background(
                if (selected) {
                    // 选中：透明，让面板凸起（FusedPanelShape）提供背景
                    Color.Transparent
                } else {
                    // 未选中：平面半透明圆角方块（无渐变、无投影）
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.40f)
                },
            )
            // 默认涟漪作轻量按压感；切换时的振动反馈由调用方 onTabClick 统一触发
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 标题已在抽屉头部显示，这里只保留图标：选中填充 / 未选中描边
        Icon(
            imageVector = if (selected) feature.iconSelected else feature.iconUnselected,
            contentDescription = feature.label,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}
