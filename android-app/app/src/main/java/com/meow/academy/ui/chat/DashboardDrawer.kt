package com.meow.academy.ui.chat

import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

/** 右侧看板锚定拖拽位置：Open = 面板完全展开（translationX 0），Closed = 面板滑出屏幕右侧 */
private enum class DrawerPos { Closed, Open }

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
 * 交互（2026-08-27 起改为锚定拖拽，复刻左抽屉 ModalNavigationDrawer 手感）：
 *  - 无黑色遮罩（透明点击区，点外部关闭）；
 *  - 头部：X 在左、功能名在右（标题区）；
 *  - 打开：顶栏「功能看板」按钮，或聊天内容区任意位置向左滑（手势在 ChatScreen 挂载）；
 *  - 面板上：**左右拖拽跟随手指**，松手时按偏移/速度自动 settle 到开/关（半拉支持）；
 *  - 面板上向右滑可收回（与左抽屉一致）。
 *
 * 状态：内部用 [AnchoredDraggableState] 管理 offset（Open=0px / Closed=panelWidthPx），
 * 外部仍以 `open: Boolean` 驱动打开/关闭；内部手势 settle 到 Closed 时回调 [onClose]。
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
    // 「工作设置」面板（plan-standard-mode §5.7）：默认空实现保持既有调用点可编译，
    // ChatScreen 接线时传 WorkspaceSettingsPanel(vm)（喵~）
    workspaceSettingsPanel: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 面板实际宽度（px）：锚点依赖它，首次布局测量后才可用
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    // 锚点是否已初始化（避免「首次测量」与「open 变化」两个 effect 竞争 animateTo）
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

    // 首次测量到面板宽度后建立锚点，并把偏移摆到与外部 open 一致的位置。
    // 注意 initialized 的时机：
    //  - open=true：先初始化（让面板可见），再 animateTo 从屏幕外滑入，产生滑入动画
    //  - open=false：先 snapTo 移到屏幕外（关闭位置），再初始化（面板始终不可见）
    // 无论如何，updateAnchors 必须在 initialized=true 之前执行，
    // 否则 alpha=1 时 offset 还是 NaN（回退到 0），面板会覆盖 95% 屏幕拦截所有点击。
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
                initialized = true  // 先让面板可见（offset=panelWidth，在屏幕外）
                dragState.animateTo(DrawerPos.Open)  // 从屏幕外滑入
            } else {
                dragState.snapTo(DrawerPos.Closed)  // 先 snap 到屏幕外
                initialized = true  // 再初始化（面板始终不可见，在屏幕外）
            }
        }
    }

    // 外部 open 变化 → 动画开/关
    LaunchedEffect(open, panelWidthPx) {
        if (panelWidthPx > 0f && initialized) {
            if (open) {
                dragState.animateTo(DrawerPos.Open)
            } else {
                dragState.animateTo(DrawerPos.Closed)
            }
        }
    }

    // 内部手势 settle 到 Closed 后同步外部状态（避免 ChatScreen 的 dashboardOpen 与面板脱节）
    LaunchedEffect(dragState.currentValue) {
        if (dragState.currentValue == DrawerPos.Closed && open) {
            onClose()
        }
    }

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

    // 关闭：统一走锚定动画（外部按钮/遮罩/BackHandler 都会落到这里）
    val close = {
        scope.launch { dragState.animateTo(DrawerPos.Closed) }
    }

    // 面板是否「正在/已经打开」：决定透明遮罩与面板交互是否启用。
    // 注意：graphicsLayer.translationX 不影响命中测试位置，面板关闭时（offset=panelWidth）
    // 视觉在屏幕外但命中测试仍在屏幕右侧，所以这里用 offset < panelWidth 判断「未完全关闭」，
    // 完全关闭时 panelActive=false → clickable/anchoredDraggable 禁用，事件穿透到下层。
    val panelActive = dragState.offset < panelWidthPx

    Box(Modifier.fillMaxSize()) {
        // 透明遮罩：没有黑色盖层，面板未打开时不拦截任何事件
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

        // 抽屉本体：左侧页签列 + 右侧融合面板（半透明 + 白色透明，性能优先）。
        // 始终参与布局，用 translationX 控制开/关位置（0=开，panelWidth=关），
        // 由 AnchoredDraggableState 驱动 offset → 支持半拉与跟手。
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.95f)
                .widthIn(max = 380.dp)
                // 标题与聊天页 TopAppBar 对齐：点击区与拖拽覆盖整块面板。
                // 注意：位置用 graphicsLayer.translationX 而非 Modifier.offset——
                // offset 是布局修饰符，实测它的命中测试不跟随偏移，导致面板滑到屏幕外后
                // clickable 仍在屏幕内拦截所有点击；graphicsLayer 是纯视觉变换，
                // 命中测试固定在屏幕右侧，所以必须用 enabled=panelActive 让面板关闭时不拦截。
                .graphicsLayer {
                    translationX = dragState.offset.takeIf { !it.isNaN() } ?: 0f
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    enabled = panelActive,
                )
                // 首次测量到宽度前不可见，避免初始 offset=0 导致面板闪现在打开位置
                // （alpha=0 同时让未初始化时的面板不参与命中测试）
                .onGloballyPositioned {
                    val w = it.size.width.toFloat()
                    if (w != panelWidthPx) panelWidthPx = w
                }
                .alpha(if (panelWidthPx > 0f && initialized) 1f else 0f)
                // 锚定拖拽：左右跟手，松手 settle（半拉）。
                // 同样用 enabled=panelActive：面板完全关闭时禁用，避免拦截下层事件。
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
                            IconButton(onClick = { close() }) {
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
