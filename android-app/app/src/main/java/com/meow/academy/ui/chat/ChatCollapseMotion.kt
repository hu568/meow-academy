package com.meow.academy.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * 聊天流式卡片的折叠动效规范与状态机（plan-chat-streaming-render §四.B）。
 *
 * 三类卡片（思考卡 [ThinkingCard] / 工具组 [ToolGroup] / 工具卡 [ToolCard]）共用这里的
 * 时长、进出场 Transition、箭头与自动收起状态机，避免各处自定义导致节奏不一致。
 *
 * 时长取值对齐既有约定（不引入新时长体系）：
 * - 320ms 收起/展开：`MainScreen` 底部导航出场 320ms / `DashboardDrawer` 300ms 同档；
 * - 460ms 延迟：参考 demo 的 `scheduleAutoCollapse()`（`setTimeout(460)`），让「已完成」可见一拍；
 * - 180ms 淡入淡出：高度由 expand/shrink 承担，透明度稍快收尾，避免「灰块拖尾」。
 *
 * ⚠️ 只做高度 + 透明度两套动画；**不要再叠 `Modifier.animateContentSize()`**
 * （同一节点两套高度动画会互相打架）。
 */

/** 完成后停顿一拍再自动收起（让用户看到「已完成」那一下） */
internal const val AUTO_COLLAPSE_DELAY_MS = 460L

/** 高度展开/收起时长 */
private const val COLLAPSE_DURATION_MS = 320

/** 透明度淡入/淡出时长 */
private const val COLLAPSE_FADE_MS = 180

/** 折叠展开：高度自顶部生长 + 淡入 */
internal val CollapseEnter =
    expandVertically(
        animationSpec = tween(COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
        expandFrom = Alignment.Top,
    ) + fadeIn(tween(COLLAPSE_FADE_MS, easing = FastOutSlowInEasing))

/** 折叠收起：高度向顶部收缩 + 淡出 */
internal val CollapseExit =
    shrinkVertically(
        animationSpec = tween(COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
        shrinkTowards = Alignment.Top,
    ) + fadeOut(tween(COLLAPSE_FADE_MS, easing = FastOutSlowInEasing))

/**
 * 当前聊天列表是否贴底跟随（`reverseLayout` 下 index 0 且 offset 0）。
 *
 * 卡片自动收起只在贴底时进行：上滑看历史时卡片高度变化会把历史内容顶走（§B5）。
 * 由 `ChatMessageList` 在 `LazyColumn` 外层下发，流式气泡与历史气泡都能读到。
 */
internal val LocalChatAtBottom = compositionLocalOf { true }

/**
 * 聊天消息列表的 [LazyListState]（由 `ChatMessageList` 在 `LazyColumn` 外层下发）。
 *
 * 只给 [CollapseAnchor] 用：折叠动画需要往列表里补偿滚动。非列表宿主（预览 / 单测）读到 null
 * 时补偿自动跳过。
 */
internal val LocalChatListState = compositionLocalOf<LazyListState?> { null }

/**
 * 折叠动画是否允许做滚动补偿（由 `ChatMessageList` 下发）。
 *
 * 贴底 + 正在流式时置 false：此时补偿会把正在生成的新内容顶出视口，
 * 破坏「流式内容贴底跟随」这条主线行为（用户手动展开卡片不值得付这个代价）。
 */
internal val LocalChatCollapseCompensation = compositionLocalOf { true }

/** 手动切换后允许补偿滚动的窗口：覆盖一次 320ms 动画 + 余量（动画是 tween，跑完必停） */
private const val MANUAL_ANCHOR_WINDOW_MS = COLLAPSE_DURATION_MS + 60L

/**
 * 折叠动画的「原地锚定」滚动补偿（§B7，2026-09-11 真机实测后补）。
 *
 * **问题（真机实测）**：聊天列表是 `reverseLayout`，LazyColumn 把每一项的**底边**钉在锚点上；
 * 卡片长高时整项向上长——实测「工具调用 x1」展开前后：卡片头 1309 → **1141（上移 168 = 正文高度）**，
 * 而同消息下方正文停在 1461 不动。观感就是「卡片往上弹」，与参考 demo（普通自上而下容器：
 * 头部不动、正文向下展开、下方内容被推下去）正好相反。
 *
 * **做法**：把卡片自身的高度增量反向补偿成列表滚动（滚动量 = 高度增量），视觉上头部原地不动、
 * 正文向下展开、下方内容被推下去。修复后实测：卡片头 Δ=0px、下方正文 +168px（= 正文高度）。
 *
 * **只在用户手动切换时补偿**（[beginManualWindow]），自动开合不补偿：
 * - 自动收起发生在贴底跟随时，补偿会把视图顶离底部，破坏「流式内容贴底」这条主线行为
 *   （后续流式文本会落在视口之外）；
 * - 自动展开（工具运行中）同理。
 *
 * **另外**：贴底 + 正在流式（`streaming != null`）时连手动补偿也关掉（见 [LocalChatCollapseCompensation]）
 * ——此时补偿同样会把正在生成的新内容顶出视口。
 */
@Stable
internal class CollapseAnchor(private val listState: LazyListState?) {

    /** 是否允许补偿（由 [LocalChatCollapseCompensation] 每帧同步；普通 var，不进 state 不触发重组） */
    internal var compensationAllowed: Boolean = true

    /** 折叠容器的当前高度（px），由 [CollapseBody] 的 `onSizeChanged` 上报 */
    private val heightPx = mutableStateOf(0)

    private var compensateUntilUptimeMs = 0L
    private var lastHeightPx = 0

    internal fun onHeight(height: Int) {
        heightPx.value = height
    }

    /** 用户手动点开 / 收起：打开补偿窗口，让本次动画的布局位移被滚动抵消 */
    internal fun beginManualWindow() {
        if (!compensationAllowed) return
        compensateUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_ANCHOR_WINDOW_MS
    }

    /** 常驻收集：高度每变一次就补偿一次（窗口外只记录、不滚动） */
    internal suspend fun run() {
        val state = listState ?: return
        snapshotFlow { heightPx.value }.collect { height ->
            val delta = height - lastHeightPx
            lastHeightPx = height
            if (delta != 0 && SystemClock.uptimeMillis() < compensateUntilUptimeMs) {
                // 正值 = 内容向下移动同样多的像素（`reverseLayout` 下滚动方向与常规列表相反）
                state.scrollBy(delta.toFloat())
            }
        }
    }
}

/** 记住一个 [CollapseAnchor] 并启动它的补偿收集协程 */
@Composable
internal fun rememberCollapseAnchor(): CollapseAnchor {
    val listState = LocalChatListState.current
    val allowed = LocalChatCollapseCompensation.current
    val anchor = remember(listState) { CollapseAnchor(listState) }
    anchor.compensationAllowed = allowed
    LaunchedEffect(anchor) { anchor.run() }
    return anchor
}

/**
 * 折叠正文容器：`AnimatedVisibility` + 把「动画中的容器高度」上报给 [CollapseAnchor]。
 *
 * 为什么外面要包一层 [Box]：`AnimatedVisibility` 自身的尺寸节点拿不到，而 `Box` 是 wrap-content，
 * 它的高度就等于动画中的高度 → `onSizeChanged` 每帧都能拿到增量（正文自身增长也会触发，
 * 靠 [CollapseAnchor.beginManualWindow] 的时间窗区分）。
 */
@Composable
internal fun CollapseBody(
    visible: Boolean,
    anchor: CollapseAnchor?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.onSizeChanged { anchor?.onHeight(it.height) },
    ) {
        AnimatedVisibility(visible = visible, enter = CollapseEnter, exit = CollapseExit) {
            content()
        }
    }
}

/**
 * 折叠指示箭头：展开朝下、收起朝右（-90°）。
 *
 * 用旋转动画而不是 `▾`/`▸` 字形互换——换字符无法做过渡，观感就是「卡一下」。
 */
@Composable
internal fun CollapseChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(COLLAPSE_DURATION_MS, easing = FastOutSlowInEasing),
        label = "collapse-chevron",
    )
    Icon(
        imageVector = Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        modifier = modifier
            .size(18.dp)
            .graphicsLayer { rotationZ = rotation },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 折叠卡自动状态机（思考卡 / 工具组共用）。
 *
 * 语义（与参考 demo 的 `userToggled` / `scheduleAutoCollapse` 对齐）：
 * - **运行中**：强制展开（看得到内容在长）；
 * - **完成**（running 变 false）：停顿 [AUTO_COLLAPSE_DELAY_MS] 再自动收起；
 * - **用户点过**：之后全手动，自动逻辑不再覆盖用户选择（用户收起就一直是收起的）；
 * - **[pinned]**（模型首次思考）：默认展开且**永不自动收起**——首段思考/输出是刻意保留给用户看的；
 * - **不贴底**（[LocalChatAtBottom] 为 false）：抑制自动收起，别把历史顶走；用户手动开合不受限。
 *
 * 为什么用「三变量」而不是单个 `expanded`：自动态与手动态必须分开存，
 * 否则「用户展开过」的信息会被自动逻辑冲掉（§B1/B2 的状态机就是这么设计的）。
 */
@Stable
internal class AutoCollapseController(running: Boolean, val pinned: Boolean) {

    /** 用户是否点过（点过 = 之后全手动） */
    var userToggled by mutableStateOf(false)
        private set

    /** 用户手动置的展开态（仅 [userToggled] 后参与判定） */
    private var manualExpanded by mutableStateOf(false)

    /** 自动态：true = 已被自动收起（初始值：非运行中即收起） */
    var autoCollapsed by mutableStateOf(!running && !pinned)
        internal set

    val expanded: Boolean
        get() = if (userToggled) manualExpanded else !autoCollapsed

    /** 点击头部：翻转当前展开态并接管（此后不再自动开合） */
    fun toggle() {
        manualExpanded = !expanded
        userToggled = true
    }
}

/**
 * 记住一个 [AutoCollapseController] 并驱动其自动逻辑。
 *
 * `LaunchedEffect` 的 key 含 `running` / `atBottom` / `userToggled`：
 * 任一变化都会取消在途的延迟收起（不需要额外的定时器字段），
 * 例如「正在收起时又开始运行」「用户点开」「滑离底部」都会立刻停止自动收起。
 */
@Composable
internal fun rememberAutoCollapse(
    running: Boolean,
    pinned: Boolean = false,
): AutoCollapseController {
    val controller = remember { AutoCollapseController(running, pinned) }
    val atBottom = LocalChatAtBottom.current
    LaunchedEffect(controller, running, atBottom, controller.userToggled) {
        if (controller.userToggled) return@LaunchedEffect
        if (running) {
            // 重新运行：立刻展开（承接上一次的收起动画）
            controller.autoCollapsed = false
        } else if (!pinned && atBottom) {
            delay(AUTO_COLLAPSE_DELAY_MS)
            controller.autoCollapsed = true
        }
    }
    return controller
}
