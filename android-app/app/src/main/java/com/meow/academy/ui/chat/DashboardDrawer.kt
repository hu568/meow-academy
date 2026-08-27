package com.meow.academy.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ModelTraining
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 功能看板三个功能页（图标：选中填充 / 未选中描边，风格同底部导航） */
enum class DashboardFeature(
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
) {
    MODELS("模型管理", Icons.Filled.ModelTraining, Icons.Outlined.ModelTraining),
    FILES("快捷文件", Icons.Filled.AttachFile, Icons.Outlined.AttachFile),
    STATS("调用量", Icons.Filled.Insights, Icons.Outlined.Insights),
}

// ─────────────────── 几何常量（真机目检可微调） ───────────────────

/** 左侧页签列宽（凸起/融合区域右缘 x） */
private val RAIL_WIDTH = 48.dp
/** 未选中页签方块边长 */
private val TAB_SQUARE = 44.dp
/** 未选中页签方块圆角（对齐面板内 SelectableRow 的 12dp，备选 8dp） */
private val TAB_CORNER = 12.dp
/** 页签列顶部 padding：让第一个页签低于抽屉标题（方便点按） */
private val RAIL_TOP_PADDING = 72.dp
/** 页签（选中/未选中统一高度，无文字） */
private val TAB_SELECTED_HEIGHT = TAB_SQUARE
/** 页签列内竖向间距 */
private val TAB_SPACING = 10.dp
/** 面板主体圆角 */
private val PANEL_CORNER = 16.dp
/** 凸起外侧圆角 / 反圆弧半径 */
private val BUMP_CORNER = 12.dp
/** 凸起与面板交界处相切圆角的半径 R（圆心偏移 = 半径，同时相切于内容区左缘与页签边） */
private val BUMP_RADIUS = 12.dp
/** 凸起水平居中于页签列时的左右偏移 */
private val BUMP_OFFSET = (RAIL_WIDTH - TAB_SQUARE) / 2
/** 内容区（头部/面板）起始 x：凸起右缘 + 少量呼吸间距 */
private val CONTENT_START_PADDING = BUMP_OFFSET + TAB_SQUARE + 4.dp

/**
 * 「毛玻璃融合」面板形状（参考 `毛玻璃融合页签` 的 clip-path 思路，纯几何剪裁、零额外模糊）：
 *
 * 面板主体（矩形，右缘贴屏幕边为直角）+ 左侧选中页签处向右伸出一个「凸起」。
 * 凸起与面板主体交界处用**相切圆角**平滑过渡：交接圆弧的圆心在交角外侧偏移一个半径 R，
 * 圆同时相切于内容区左缘与页签上/下边，形成干净的 90° 圆角——选中页签透明无背景，
 * 由这个凸起供底，视觉上页签与面板融为一体。
 *
 * 路径按抽屉局部坐标绘制：x=0 为抽屉左缘（页签列），x=W 为抽屉右缘（屏幕边）。
 *
 * @param t1 / t2 凸起的顶/底 y（抽屉局部坐标，由选中页签 bounds 换算）
 * @param bumpLeft / bumpRight 凸起的左/右 x（= 选中页签水平 bounds）
 */
private class FusedPanelShape(
    private val t1: Float,
    private val t2: Float,
    private val bumpLeft: Float,
    private val bumpRight: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val W = size.width
        val H = size.height
        val r = with(density) { PANEL_CORNER.toPx() }   // 面板圆角
        val rc = with(density) { BUMP_CORNER.toPx() }   // 凸起外侧圆角
        val R = with(density) { BUMP_RADIUS.toPx() }    // 反圆弧半径
        val L = bumpLeft
        val B = bumpRight
        // 防退化：凸起高度至少盖住两个反圆弧，避免动画中间帧出现自交路径
        val y1 = t1.coerceAtLeast(r + R)
        val y2 = t2.coerceAtLeast(y1 + 2 * R + 4)

        val path = Path().apply {
            moveTo(W, 0f)
            lineTo(W, H)                                                  // 右缘（贴屏幕边，直角）
            lineTo(B + r, H)
            arcTo(Rect(B, H - 2 * r, B + 2 * r, H), 90f, 90f, false)      // 面板左下圆角
            lineTo(B, y2 + R)
            // ★交接圆角（下）：圆心在页签与内容区交角外侧 (B-R, y2+R)，偏移一个半径 R，
            //   圆同时相切于内容区左缘（切点 (B, y2+R)）与页签下边（切点 (B-R, y2)），90° 平滑过渡
            arcTo(Rect(B - 2 * R, y2, B, y2 + 2 * R), 0f, -90f, false)
            lineTo(L + rc, y2)
            arcTo(Rect(L, y2 - 2 * rc, L + 2 * rc, y2), 90f, 90f, false)  // 凸起左下圆角
            lineTo(L, y1 + rc)
            arcTo(Rect(L, y1, L + 2 * rc, y1 + 2 * rc), 180f, 90f, false) // 凸起左上圆角
            lineTo(B - R, y1)
            // ★交接圆角（上）：圆心在 (B-R, y1-R)，相切于页签上边（切点 (B-R, y1)）
            //   与内容区左缘（切点 (B, y1-R)），90° 平滑过渡
            arcTo(Rect(B - 2 * R, y1 - 2 * R, B, y1), 90f, -90f, false)
            lineTo(B, r)
            arcTo(Rect(B, 0f, B + 2 * r, 2 * r), 180f, 90f, false)        // 面板左上圆角
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * 右侧「功能看板」抽屉（Chatbox 风格，自绘 overlay）。
 *
 * 布局：左侧竖向模式页签（图标，选中填充 / 未选中描边，带振动反馈）+ 右侧融合面板。
 * 融合：选中页签透明无背景，与面板通过 [FusedPanelShape] 相切圆角剪裁连为一体；
 * 未选中页签为半透明平面圆角方块 + 描边图标（参考毛玻璃融合页签 + 底部导航风格）。
 *
 * 性能：抽屉本体仅白色透明（surface 80%），背景模糊由 ChatScreen 打开抽屉时统一 blur，
 * 这里不做任何逐元素 backdrop 模糊。
 *
 * 交互：
 *  - 无黑色遮罩（透明点击区，点外部关闭）；
 *  - 头部：X 在左、功能名在右（标题区）；
 *  - 打开：顶栏「功能看板」按钮，或聊天内容区任意位置向左滑（手势在 ChatScreen 挂载）；
 *  - 面板上向右滑可收回（与左抽屉一致）。
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
    val density = LocalDensity.current
    // 凸起水平 bounds（页签列 48dp 内居中 44dp 方块）
    val bumpLeftPx = with(density) { BUMP_OFFSET.toPx() }
    val bumpRightPx = with(density) { (BUMP_OFFSET + TAB_SQUARE).toPx() }
    // 测量前/动画期间用默认凸起位置：按当前选中页签算术估算（页签列固定布局），
    // 保证抽屉首次打开时凸起就在正确页签上，不产生从顶部滑落的首帧位移
    val selectedIndex = DashboardFeature.entries.indexOf(feature).coerceAtLeast(0)
    val defaultT1 = with(density) {
        (RAIL_TOP_PADDING + (TAB_SQUARE + TAB_SPACING) * selectedIndex).toPx()
    }
    val defaultT2 = defaultT1 + with(density) { TAB_SELECTED_HEIGHT.toPx() }

    // 选中页签 bounds（window 坐标）→ 凸起 t1/t2（裁剪表面局部坐标）。
    // 基准 = 裁剪表面 Box 自身 bounds（其本地坐标系即 clip 形状坐标系）。
    var selectedBounds by remember { mutableStateOf<Rect?>(null) }
    var surfaceBounds by remember { mutableStateOf<Rect?>(null) }
    val rawT1 = if (selectedBounds != null && surfaceBounds != null) {
        selectedBounds!!.top - surfaceBounds!!.top
    } else {
        defaultT1
    }
    val rawT2 = if (selectedBounds != null && surfaceBounds != null) {
        selectedBounds!!.bottom - surfaceBounds!!.top
    } else {
        defaultT2
    }
    // 凸起平滑滑动到对应页签（对应参考的 transition: clip-path .45s）
    val animT1 by animateFloatAsState(
        targetValue = rawT1,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "dashboardBumpTop",
    )
    val animT2 by animateFloatAsState(
        targetValue = rawT2,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "dashboardBumpBottom",
    )
    val fusedShape = remember(animT1, animT2, bumpLeftPx, bumpRightPx) {
        FusedPanelShape(animT1, animT2, bumpLeftPx, bumpRightPx)
    }

    val haptics = LocalHapticFeedback.current
    val onTabClick: (DashboardFeature) -> Unit = { f ->
        // 真正切换时才振动（与底部导航一致），点当前页签不重复振
        if (f != feature) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onFeatureChange(f)
    }

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
                // 抽屉本体：左侧页签列 + 右侧融合面板（半透明 + 白色透明，性能优先）
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.95f)
                        .widthIn(max = 380.dp)
                        // 标题与聊天页 TopAppBar 对齐：点击区与右滑收回覆盖整块面板
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
                    // 内容容器：统一做系统栏 padding，所有子元素以此为坐标基准
                    Box(
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    ) {
                        // ① 融合表面 + 内容：整层按 FusedPanelShape 剪裁 + 白色透明背景
                        //    onGloballyPositioned 测量自身 bounds 作为凸起坐标基准
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { surfaceBounds = it.boundsInWindow() }
                                .clip(fusedShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)),
                        ) {
                            Column(
                                Modifier
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
                                        }
                                    }
                                }
                            }
                        }
                        // ② 左侧竖向模式页签列（不参与融合剪裁，选中页签透明靠凸起供底）
                        Column(
                            Modifier
                                .align(Alignment.TopStart)
                                .width(RAIL_WIDTH)
                                .fillMaxHeight()
                                .padding(top = RAIL_TOP_PADDING),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TAB_SPACING),
                        ) {
                            DashboardFeature.entries.forEach { f ->
                                FeatureTab(
                                    feature = f,
                                    selected = f == feature,
                                    reportBounds = if (f == feature) { rect -> selectedBounds = rect } else null,
                                    onClick = { onTabClick(f) },
                                )
                            }
                        }
                    }
                }
            }
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
