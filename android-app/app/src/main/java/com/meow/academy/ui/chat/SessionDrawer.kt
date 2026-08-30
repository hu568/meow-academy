package com.meow.academy.ui.chat

/**
 * 会话抽屉组件（聊天页左侧）。
 * 会话列表 + 新建 + 多选（右滑触发 / 工具栏按钮）+ 长按菜单（重命名/删除）+ 对话框；从 ChatScreen.kt 原子拆出。
 *
 * 工作区过滤 + 三行会话行（plan-standard-mode §5.8）：
 * - 标题行「过滤」图标 → DropdownMenu 单选「全部会话 / 当前工作区会话」（持久化 vm.sessionFilter）；
 *   workspace 档客户端过滤 workspacePath == 新会话默认工作区；空态引导去工作设置页；
 * - 会话行三行布局：标题 / 预设名 · 工作区短名（仅「全部会话」且非默认工作区时带工作区段）/ 紧凑时间；
 * - ChatViewModel 经 viewModel() 从同一 ViewModelStore 解析（与 ChatScreen 同实例），
 *   对外签名零变化（喵~）。
 *
 * 多选交互（仿 MT 管理器）：
 * - 工具栏「☑ 清单」按钮：进入多选模式
 * - 列表项「右滑」过阈值：自动进入多选并勾选该项
 * - 多选模式下点击：切换勾选；非多选：打开会话
 * - 多选工具栏：「✕」退出多选 + 「🗑」批量删除（带确认）
 *
 * 单条会话操作：
 * - 普通模式长按卡片：弹出菜单（重命名 / 删除），卡片右侧不再常驻操作按钮
 *
 * 手势注意：行内的「右滑多选 / 点击 / 长按」手势统一在同一个 pointerInput 里处理、
 * 只观察不消费——右滑才累计位移触发多选，左滑完全让出事件，保留 ModalNavigationDrawer 的左滑收回抽屉。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.ui.components.EmptyStateCompact
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 右滑触发多选的阈值（px 在 density 中换算） */
private val SWIPE_TRIGGER_DP = 64.dp

/**
 * epoch millis → 紧凑时间（plan-standard-mode §5.8，去秒，喵~）：
 * 今天 `HH:mm`、今年 `M月d日 HH:mm`、跨年 `yyyy年M月d日`。
 */
internal fun formatSessionTimestamp(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameYear = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val sameDay = sameYear && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        sameYear -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(millis))
    }
}

/** 会话抽屉：列表 + 新建 + 多选 + 重命名/删除对话框 */
@Composable
fun SessionDrawer(
    sessions: List<SessionEntity>,
    currentId: Long?,
    drawerOpen: Boolean,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (SessionEntity) -> Unit,
    onDeleteMany: (List<SessionEntity>) -> Unit,
    onRename: (Long, String) -> Unit,
) {
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    var batchDeleting by remember { mutableStateOf(false) }

    // ── 工作区过滤 + 元信息行数据源（plan-standard-mode §5.8） ──
    // 同一 ViewModelStore 内解析 ChatViewModel（与 ChatScreen 的 vm 同实例，喵~）
    val chatVm: ChatViewModel = viewModel()
    val sessionFilter by chatVm.sessionFilter.collectAsState()
    val defaultWorkspacePath by chatVm.defaultWorkspacePath.collectAsState()
    val presetCatalog by chatVm.presetCatalog.collectAsState()
    val filesDirPath = LocalContext.current.filesDir.absolutePath

    // 会话显示过滤（客户端过滤，会话量级小）：workspace 档只显示默认工作区的会话
    val filteredSessions = remember(sessions, sessionFilter, defaultWorkspacePath) {
        if (sessionFilter == "workspace") {
            sessions.filter { it.workspacePath == defaultWorkspacePath }
        } else {
            sessions
        }
    }

    var filterMenuOpen by remember { mutableStateOf(false) }

    // 多选状态：选中的会话 id 集合；空集合 + selectionMode=false → 普通模式
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 退出多选的统一入口：清空选择 + 关闭模式
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedIds = emptySet()
    }

    // 抽屉关闭时自动退出多选（避免「用户侧滑关闭抽屉后下次打开还残留多选 UI」）。
    // 关键是 onDrawerClose 触发时 drawerOpen: true → false 这一刻执行 exitSelection()。
    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) exitSelection()
    }

    // 切换某条会话的勾选状态（首次进入多选时也用它）
    val toggleSelected: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    // 当前已选实体（按会话列表顺序）
    val selectedSessions by remember(sessions, selectedIds) {
        derivedStateOf { sessions.filter { it.id in selectedIds } }
    }

    ModalDrawerSheet(
        // 只占约 85% 宽度，右侧留一条聊天页可见；半透明容器露出模糊后的聊天内容（毛玻璃）。
        // 上下悬浮与右抽屉（DashboardDrawer）对齐：modifier 上垫 statusBarsPadding/
        // navigationBarsPadding，面板背景不再贴应用边缘——顶部收进状态栏、底部收进系统
        // 导航栏，上下留出缝隙、圆角露出来；底部另由 ChatScreen 外层 Box 的 bottomPadding
        // 垫到应用底部导航栏上方（与 DashboardDrawer 外层同款 padding，喵~）
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .widthIn(max = 340.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        // 右侧上下圆角显式声明（镜像右抽屉 FusedPanelShape 的 16dp：非贴屏幕边的一侧圆角）
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        // 关闭 ModalDrawerSheet 自带的 systemBars insets（避免与 modifier 上的 insets padding 叠两层）：
        // 内容顶部由内部 Column 的 statusBarsPadding 兜底（insets 已在 sheet 上消费，实际为 0，
        // 保留它是防御性的——若外层 padding 调整，标题行仍与聊天页 TopAppBar 对齐）
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        // 内容容器：仅顶部避开状态栏（标题与聊天页标题对齐），底部不额外垫系统栏
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // 工具栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    // 多选模式工具栏：✕ 退出 + 标题（已选 N 项）+ 删除
                    IconButton(onClick = exitSelection) {
                        Icon(Icons.Filled.Close, contentDescription = "取消多选")
                    }
                    Text(
                        text = "已选 ${selectedIds.size} 项",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    IconButton(
                        onClick = { if (selectedSessions.isNotEmpty()) batchDeleting = true },
                        enabled = selectedSessions.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "批量删除",
                            tint = if (selectedSessions.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 普通模式工具栏：标题 + 过滤 + 多选（紧贴新建左边） + 新建
                    Text(
                        text = "会话",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    // 显示过滤入口：全部会话 / 当前工作区会话（DropdownMenu 单选，持久化到 DataStore）
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                Icons.Outlined.FilterList,
                                contentDescription = "会话显示过滤",
                                tint = if (sessionFilter == "workspace") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部会话") },
                                leadingIcon = {
                                    if (sessionFilter != "workspace") {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    chatVm.setSessionFilter("all")
                                    filterMenuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("当前工作区会话") },
                                leadingIcon = {
                                    if (sessionFilter == "workspace") {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    chatVm.setSessionFilter("workspace")
                                    filterMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = {
                        selectionMode = true
                        selectedIds = emptySet()
                    }) {
                        Icon(
                            Icons.Outlined.ChecklistRtl,
                            contentDescription = "多选",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Filled.Add, contentDescription = "新建会话")
                    }
                }
            }

            when {
                sessions.isEmpty() -> {
                    EmptyStateCompact(
                        icon = Icons.Outlined.Forum,
                        title = "暂无会话",
                    )
                }
                // workspace 档空态：引导去工作设置页添加/切换工作区（喵~）
                filteredSessions.isEmpty() -> {
                    Column(Modifier.fillMaxWidth()) {
                        EmptyStateCompact(
                            icon = Icons.Outlined.Forum,
                            title = "当前工作区还没有会话",
                        )
                        Text(
                            text = "到右侧看板 → 工作设置 可添加或切换工作区喵~",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(filteredSessions, key = { it.id }) { session ->
                            val isSelected = session.id in selectedIds
                            // 元信息行：预设显示名（常显）+「 · 」+ 工作区短名
                            // （仅「全部会话」模式且会话不属于当前默认工作区时显示，喵~）
                            val presetLabel = when (val pid = session.presetId) {
                                null -> "默认"
                                else -> presetCatalog.firstOrNull { it.id == pid }?.let { it.name ?: it.id } ?: pid
                            }
                            val showWorkspace = sessionFilter == "all" && session.workspacePath != defaultWorkspacePath
                            val metaLine = if (showWorkspace) {
                                "$presetLabel · " + workspaceShortName(session.workspacePath, filesDirPath)
                            } else {
                                presetLabel
                            }
                            SwipeableSessionRow(
                                session = session,
                                isCurrent = session.id == currentId,
                                selectionMode = selectionMode,
                                isSelected = isSelected,
                                metaLine = metaLine,
                                onTap = {
                                    if (selectionMode) toggleSelected(session.id)
                                    else onOpen(session.id)
                                },
                                onSwipeRightTrigger = {
                                    // 右滑触发：进入多选 + 勾选该项
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedIds = setOf(session.id)
                                    } else {
                                        toggleSelected(session.id)
                                    }
                                },
                                onEdit = { renaming = session },
                                onDelete = { deleting = session },
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(session.id, title); renaming = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「" + session.title + "」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { onDelete(session); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    if (batchDeleting) {
        AlertDialog(
            onDismissRequest = { batchDeleting = false },
            title = { Text("批量删除会话") },
            text = { Text("确定删除已选的 ${selectedSessions.size} 个会话吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = selectedSessions
                    batchDeleting = false
                    exitSelection()
                    onDeleteMany(toDelete)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { batchDeleting = false }) { Text("取消") } },
        )
    }
}

/**
 * 单个会话行（三行布局，plan-standard-mode §5.8，喵~）：
 *   会话标题      ← titleMedium，maxLines=1 + ellipsis
 *   预设 · 工作区 ← 元信息行（bodySmall，单行 ellipsis，预设名在前）
 *   紧凑时间      ← labelSmall（今天 HH:mm / 今年 M月d日 HH:mm / 跨年带年）
 * - 多选模式 → 右侧复选框；点击行切换勾选；长按不弹菜单（多选工具栏的删除按钮统一处理）
 * - 普通模式 → 点击行打开会话；长按弹出菜单（重命名/删除）
 * - 右滑：行向右偏移累计，超阈值回调 onSwipeRightTrigger 一次；手指松开回弹归零
 *   （避免遮挡/手感僵硬）。
 * - 左滑：完全不处理也不消费，事件冒泡给外层 ModalNavigationDrawer → 抽屉左滑收回。
 *
 * **首次右滑进入多选时不要卡住**：把 `selectionMode` 排除在 `pointerInput` 的 key 之外，
 * 否则右滑触发 onSwipeRightTrigger → 外层 selectionMode 变 true → Row 重组 →
 * pointerInput 因 key 变化被销毁重建 → 正在进行的 drag 事件丢失、onDragEnd 永远不到 →
 * dragOffsetX 永远保留位移（用户看到卡片"卡住"）。
 * 解法：key 只用 session.id，dragOffsetX 用 animateFloatAsState 让其平滑归零
 * （selectionMode 变化时任何非零偏移都自然 animateTo(0f)）。
 *
 * 点击/长按由 combinedClickable 处理，右滑由 pointerInput 处理（只观察不消费），
 * 互不抢事件；左滑完全让出，保留 ModalNavigationDrawer 的收回手势。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableSessionRow(
    session: SessionEntity,
    isCurrent: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    metaLine: String,
    onTap: () -> Unit,
    onSwipeRightTrigger: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val triggerPx = with(density) { SWIPE_TRIGGER_DP.toPx() }
    // 长按菜单展开状态
    var longPressMenuExpanded by remember { mutableStateOf(false) }
    // 行的水平拖拽偏移（用于滑动时的视觉反馈，松手/触发后归零）
    var dragOffsetX by remember { mutableStateOf(0f) }
    // selectionMode / isCurrent 变化时把任何残留的拖拽偏移弹回 0
    LaunchedEffect(selectionMode, isCurrent) {
        dragOffsetX = 0f
    }
    // 平滑回弹：dragOffsetX 变化 → 200ms 动画到目标值（自然弹回）
    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = tween(durationMillis = 180),
        label = "sessionRowSwipe",
    )
    // 让 pointerInput 永远读到最新的 onSwipeRightTrigger（避免闭包捕获旧值）
    val currentOnSwipeRightTrigger by rememberUpdatedState(onSwipeRightTrigger)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .background(
                    if (isCurrent && !selectionMode) MaterialTheme.colorScheme.surfaceVariant
                    else Color.Transparent,
                )
                // 点击打开会话；长按弹出操作菜单（多选模式下长按不弹）
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { if (!selectionMode) longPressMenuExpanded = true },
                )
                // key 只用 session.id：不让 selectionMode 重建 drag handler（避免首次右滑卡住）
                // 注意：这里不能再用 detectHorizontalDragGestures——它会把左滑/右滑全部消费掉，
                // 导致左侧 ModalNavigationDrawer 收不到左滑事件、抽屉无法左滑收回。
                // 改为「只观察不消费」的手势：右滑累计位移触发多选，左滑完全不管、让事件冒泡给抽屉。
                .pointerInput(session.id) {
                    var hasTriggered = false
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange().x
                            // 只处理向右拖动；左滑不累计也不消费（抽屉收回手势才能收到）
                            if (delta > 0) {
                                dragOffsetX = (dragOffsetX + delta).coerceAtLeast(0f)
                                if (!hasTriggered && dragOffsetX >= triggerPx) {
                                    hasTriggered = true
                                    currentOnSwipeRightTrigger()
                                }
                            }
                            // 不调用 change.consume()：始终把事件留给外层抽屉手势
                        }
                        // 手指松开：视觉位移回弹归零
                        dragOffsetX = 0f
                        hasTriggered = false
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 三行布局：标题 / 预设名 · 工作区短名 / 紧凑时间（行高约 +16dp，真机目检列表密度）
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSessionTimestamp(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (selectionMode) {
                // 多选模式：右侧显示复选框（用 Checkbox 控件 + 圆形浅色背景增加点击命中）
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onTap() },
                )
            } else {
                // 普通模式：右侧预留与多选 Checkbox 等宽的占位，
                // 避免切换多选时标题/卡片内容发生横向错位
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // 长按弹出的会话操作菜单
        DropdownMenu(
            expanded = longPressMenuExpanded,
            onDismissRequest = { longPressMenuExpanded = false },
            offset = DpOffset(120.dp, 0.dp),
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    longPressMenuExpanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    longPressMenuExpanded = false
                    onDelete()
                },
            )
        }
    }
}
