package com.meow.academy.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 功能看板四个功能页（图标：选中填充 / 未选中描边，风格同底部导航） */
enum class DashboardFeature(
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
) {
    MODELS("模型管理", Icons.Filled.ModelTraining, Icons.Outlined.ModelTraining),
    FILES("快捷文件", Icons.Filled.AttachFile, Icons.Outlined.AttachFile),
    STATS("调用量", Icons.Filled.Insights, Icons.Outlined.Insights),
    WORKSPACE_SETTINGS("工作设置", Icons.Filled.Tune, Icons.Outlined.Tune),
}

/**
 * 右侧「功能看板」抽屉（Chatbox 风格，自绘 overlay）：左侧竖向模式页签 + 右侧融合面板。
 * 融合：选中页签透明无背景，由 [FusedPanelShape] 相切圆角凸起供底，与面板连为一体；
 * 未选中页签为半透明平面圆角方块 + 描边图标。背景模糊由 ChatScreen 打开抽屉时统一 blur。
 *
 * 交互（锚定拖拽，复刻左抽屉 ModalNavigationDrawer 手感）：无黑色遮罩（点外部关闭）；
 * 头部 X 在左、功能名在右；面板上左右拖拽跟手、松手按偏移/速度 settle 到开/关（半拉）；
 * 面板上向右滑可收回。内部用 [AnchoredDraggableState] 管理 offset（Open=0 / Closed=panelWidth），
 * 外部以 `open: Boolean` 驱动；内部手势 settle 到 Closed 时回调 [onClose]。
 *
 * 拆分（2026-09-01）：本文件为薄壳——状态机 + 3 个 LaunchedEffect + 凸起几何计算 + 遮罩 +
 * 抽屉本体 Box，装配 [DashboardTabRail] / [DashboardPanelContent]；纯几何在
 * DashboardDrawerGeometry.kt（[DrawerPos] / 常量 / [FusedPanelShape] / bounds 计算）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardDrawer(
    open: Boolean,
    feature: DashboardFeature,
    onFeatureChange: (DashboardFeature) -> Unit,
    onClose: () -> Unit,
    modelPanel: @Composable () -> Unit,
    filesPanel: @Composable () -> Unit,
    statsPanel: @Composable () -> Unit,
    // 「工作设置」面板（plan-standard-mode §5.7）：默认空实现保持既有调用点可编译（喵~）
    workspaceSettingsPanel: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    // 锚定拖拽状态机（与 Material3 DrawerState 同源）：Open=0 / Closed=panelWidth
    val dragState = remember {
        AnchoredDraggableState<DrawerPos>(
            DrawerPos.Closed,
            { totalDistance -> totalDistance * 0.5f },
            { with(density) { 125.dp.toPx() } },
            tween(durationMillis = 300, easing = FastOutSlowInEasing),
            exponentialDecay(),
            { true },
        )
    }

    // 首次测量后建立锚点并摆到与外部 open 一致：open=true 先初始化再 animateTo 滑入（产生滑入动画），
    // open=false 先 snapTo 屏外再初始化（始终不可见）。updateAnchors 必须在 initialized 前执行，
    // 否则 alpha=1 时 offset 仍 NaN（回退 0）面板会覆盖屏幕拦截所有点击。
    LaunchedEffect(panelWidthPx) {
        if (panelWidthPx > 0f && !initialized) {
            dragState.updateAnchors(
                DraggableAnchors {
                    DrawerPos.Closed at panelWidthPx
                    DrawerPos.Open at 0f
                },
                DrawerPos.Closed,
            )
            if (open) {
                initialized = true
                dragState.animateTo(DrawerPos.Open)
            } else {
                dragState.snapTo(DrawerPos.Closed)
                initialized = true
            }
        }
    }

    // 外部 open 变化 → 动画开/关
    LaunchedEffect(open, panelWidthPx) {
        if (panelWidthPx > 0f && initialized) {
            if (open) dragState.animateTo(DrawerPos.Open)
            else dragState.animateTo(DrawerPos.Closed)
        }
    }

    // 内部手势 settle 到 Closed 后同步外部状态（避免 dashboardOpen 与面板脱节）
    LaunchedEffect(dragState.currentValue) {
        if (dragState.currentValue == DrawerPos.Closed && open) onClose()
    }

    // 凸起几何计算（animateFloatAsState 是 Compose 运行时，留在薄壳；纯函数在 Geometry 文件）
    val (bumpLeftPx, bumpRightPx) = bumpHorizontalBounds(density)
    // 测量前/动画期间用默认凸起位置（按选中页签序号估算），避免首帧凸起从顶部滑落
    val selectedIndex = DashboardFeature.entries.indexOf(feature).coerceAtLeast(0)
    val (defaultT1, defaultT2) = defaultBumpBounds(selectedIndex, density)

    // 选中页签 bounds（window 坐标）→ 凸起 t1/t2（裁剪表面局部坐标，基准 = 裁剪表面 Box bounds）
    var selectedBounds by remember { mutableStateOf<Rect?>(null) }
    var surfaceBounds by remember { mutableStateOf<Rect?>(null) }
    val (rawT1, rawT2) = if (selectedBounds != null && surfaceBounds != null) {
        localBumpBounds(selectedBounds!!, surfaceBounds!!)
    } else {
        defaultT1 to defaultT2
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

    // 关闭：统一走锚定动画（外部按钮/遮罩/BackHandler 都会落到这里）
    val close = {
        scope.launch { dragState.animateTo(DrawerPos.Closed) }
    }

    // 面板是否「正在/已经打开」：决定透明遮罩与面板交互是否启用。
    // graphicsLayer.translationX 不影响命中测试，面板关闭时视觉在屏外但命中测试仍在右侧，
    // 故用 offset < panelWidth 判断；完全关闭时 clickable/anchoredDraggable 禁用、事件穿透。
    val panelActive = dragState.offset < panelWidthPx

    Box(Modifier.fillMaxSize()) {
        // 透明遮罩：无黑色盖层，面板未打开时不拦截任何事件
        if (panelActive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { close() },
                    ),
            )
        }

        // 抽屉本体：始终参与布局，translationX 控制开/关位置（0=开 / panelWidth=关），
        // 由 AnchoredDraggableState 驱动 offset → 支持半拉与跟手。
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.95f)
                .widthIn(max = 380.dp)
                // 位置用 graphicsLayer.translationX 而非 Modifier.offset：offset 命中测试不跟随偏移，
                // 面板滑出屏外后 clickable 仍在屏内拦截点击；graphicsLayer 是纯视觉变换，
                // 命中测试固定在右侧，故用 enabled=panelActive 让面板关闭时不拦截。
                .graphicsLayer {
                    translationX = dragState.offset.takeIf { !it.isNaN() } ?: 0f
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    enabled = panelActive,
                )
                // 首次测量到宽度前不可见（alpha=0 同时让未初始化面板不参与命中测试）
                .onGloballyPositioned {
                    val w = it.size.width.toFloat()
                    if (w != panelWidthPx) panelWidthPx = w
                }
                .alpha(if (panelWidthPx > 0f && initialized) 1f else 0f)
                // 锚定拖拽：左右跟手，松手 settle（半拉）；面板完全关闭时禁用避免拦截下层
                .anchoredDraggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = panelActive,
                ),
        ) {
            // 内容容器：统一做系统栏 padding，所有子元素以此为坐标基准
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // ① 融合表面 + 内容：整层按 FusedPanelShape 剪裁 + 白色透明背景；
                //    onGloballyPositioned 测量自身 bounds 作为凸起坐标基准
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { surfaceBounds = it.boundsInWindow() }
                        .clip(fusedShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)),
                ) {
                    DashboardPanelContent(
                        feature = feature,
                        onClose = { close() },
                        modelPanel = modelPanel,
                        filesPanel = filesPanel,
                        statsPanel = statsPanel,
                        workspaceSettingsPanel = workspaceSettingsPanel,
                    )
                }
                // ② 左侧竖向模式页签列（不参与融合剪裁，选中页签透明靠凸起供底）
                DashboardTabRail(
                    selectedFeature = feature,
                    onFeatureClick = onFeatureChange,
                    onReportBounds = { selectedBounds = it },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}
