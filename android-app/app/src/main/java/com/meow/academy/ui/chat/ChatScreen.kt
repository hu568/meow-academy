package com.meow.academy.ui.chat

/**
 * 聊天页骨架（原子拆分后的「结构」层）。
 * 只负责组装：抽屉（SessionDrawer.kt）+ Scaffold + 消息列表（MessageBubbles.kt）+ 输入栏（ChatInputBar.kt）；
 * 状态与流式逻辑在 ChatViewModel.kt，模型/序列化在 ChatSegment.kt / ChatSegmentJson.kt。
 */

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.ui.components.EmptyState
import com.meow.academy.ui.files.FilesViewModel
import kotlinx.coroutines.launch

/**
 * 💬 聊天页（Chatbox 风格）：单页详情 + 左侧会话抽屉 + 顶栏新会话 + 输入栏工具栏。
 * 页面骨架只负责组装；抽屉 / 气泡 / 输入栏 / Markdown 各在独立文件中。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    bottomPadding: Dp = 0.dp,
    imeZoom: Float = 0f,
) {
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    ChatDetailView(
        vm = vm,
        sessions = sessions,
        currentId = currentId,
        bottomPadding = bottomPadding,
        imeZoom = imeZoom,
    )
}

// ─────────────────── 会话详情 ───────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailView(
    vm: ChatViewModel,
    sessions: List<SessionEntity>,
    currentId: Long?,
    bottomPadding: Dp = 0.dp,
    imeZoom: Float = 0f,
) {
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val llmModel by vm.llmModel.collectAsState()
    val reasoningEffort by vm.reasoningEffort.collectAsState()
    val webSearchEnabled by vm.webSearchEnabled.collectAsState()
    val providers by vm.providers.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val currentProvider by vm.currentProvider.collectAsState()
    val pendingCount by vm.pendingCount.collectAsState()
    val chatBackground by vm.chatBackground.collectAsState()
    // ── 附加模式 / 悬浮栏 / 问答 / 工作设置（plan-standard-mode） ──
    val attachedMode by vm.attachedMode.collectAsState()
    val todoState by vm.todoState.collectAsState()
    val subagentRuns by vm.subagentRuns.collectAsState()
    val pendingQuestion by vm.pendingQuestion.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val defaultPreset by vm.defaultPreset.collectAsState()
    val defaultWorkspacePath by vm.defaultWorkspacePath.collectAsState()
    val presetCatalog by vm.presetCatalog.collectAsState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val attachedPaths = attachments.map { it.path }.toSet()
    // 右侧功能看板：开合 + 当前功能页（快捷文件用独立 FilesViewModel，key 固定避免与文件页互踩）
    var dashboardOpen by rememberSaveable { mutableStateOf(false) }
    val dashboardFeature by vm.dashboardFeature.collectAsState()
    val quickVm: FilesViewModel = viewModel(key = "quickFiles")
    val sessionUsageStats by vm.sessionUsageStats.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) { FileRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 右侧功能看板滑出手势：挂在聊天内容区（顶栏与输入栏之间），
    // 和左抽屉一样在窗口内容区向左滑即可打开；不会用覆盖条挡住右上角按钮。
    // 上传文件：内容去重复制到 DSH_UPLOAD_DIR（uploads/）→ 只加到输入框上方附件预览，不自动引用。
    // 点击附件预览才插入 [引用标记]；同一个文件内容相同只存一份，可反复点击引用（喵~）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val uploadDir = RuntimeExtractor.workspaceUploadsDir(context).absolutePath
                val result = repository.importDeduplicated(uri, uploadDir)
                if (result != null) {
                    val existing = attachments.firstOrNull { it.path == result.file.absolutePath }
                    if (existing == null) {
                        quickVm.recordRecent(result.file.absolutePath) // 上传即使用（喵~）
                        val refId = nextAttachmentRefId(attachments, result.file.name)
                        attachments = attachments + PendingAttachment(refId, result.file.name, result.file.absolutePath)
                    }
                } else {
                    snackbarHostState.showSnackbar("上传文件失败")
                }
            }
        }
    }

    // 快速附加（右侧看板「快捷文件」）：点文件加入输入框附件，再点取消；目录不附加
    val onToggleAttach: (FileEntry) -> Unit = { file ->
        val existing = attachments.firstOrNull { it.path == file.path }
        if (existing == null) {
            // 附加即使用：记入「最近使用」，最近模式里置顶（喵~）
            quickVm.recordRecent(file.path)
            attachments = attachments + PendingAttachment(
                refId = nextAttachmentRefId(attachments, file.name),
                displayName = file.name,
                path = file.path,
            )
        } else {
            attachments = attachments.filterNot { it.refId == existing.refId }
            input = input.replace("[${existing.refId}]", "").replace(Regex("\\s+"), " ").trim()
        }
    }

    // ── 脱离自动滚动（Chatbox 风格）──
    // reverseLayout 下 index 0 = 屏幕底部。贴底时列表天然跟随新内容（流式增长/新消息），
    // 不需要也不应该每 token 调 scrollToItem（否则高频抽搐/文字重叠）；
    // 用户上滑离开底部即脱离跟随，滑回底部即恢复跟随。
    val isAtBottom by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // ── 流式气泡冻结快照 ──
    // 贴底跟随：实时渲染最新 segments，并同步刷新快照；
    // 上滑脱离：渲染冻结快照（离开底部那一刻的内容），流式在后台继续增长但不参与布局。
    // 这样看历史时气泡高度不变，LazyColumn 不会因流式气泡长高而把历史内容顶得跳动。
    val snapshotHolder = remember(streaming?.messageId) {
        StreamingSegmentsSnapshot().also { it.segments = streaming?.segments }
    }
    val currentStreaming = streaming
    if (isAtBottom && currentStreaming != null) {
        snapshotHolder.segments = currentStreaming.segments
    }
    val displayedStreamingSegments = if (isAtBottom) currentStreaming?.segments else snapshotHolder.segments

    // 打开会话：默认回到底部（跟随）
    LaunchedEffect(currentId) {
        listState.scrollToItem(0)
    }

    // ── 自动打开最近会话（plan-standard-mode §1.1.9）──
    // 进入聊天页 currentId 为空且已有会话 → 打开 updatedAt 最新一条（列表已按 updatedAt 倒序）；
    // 删除当前会话后 currentId 归空，同样落位到最近剩余会话——一个机制覆盖两个场景，喵~
    LaunchedEffect(sessions, currentId) {
        if (currentId == null && sessions.isNotEmpty()) {
            sessions.firstOrNull()?.let { vm.openSession(it.id) }
        }
    }

    // ── 问答卡交互绑定（§5.6）──
    // 当前会话最新一个「未回答」的问答卡 call.id（消息流 + 流式一起找最后一个；result 非空 = 已答）；
    // pendingQuestion 属于当前会话时才启用交互。
    val latestQuestionCallId = remember(messages, streaming, currentId) {
        sequence {
            messages.forEach { m ->
                parseSegments(m.segmentsJson)?.forEach { seg ->
                    if (seg is Segment.Tool && seg.call.name in QuestionToolNames && seg.call.result.isBlank()) {
                        yield(seg.call.id)
                    }
                }
            }
            streaming?.segments?.forEach { seg ->
                if (seg is Segment.Tool && seg.call.name in QuestionToolNames && seg.call.result.isBlank()) {
                    yield(seg.call.id)
                }
            }
        }.lastOrNull()
    }
    val pendingQuestionForSession = pendingQuestion?.takeIf { pq ->
        pq.sessionId == null || pq.sessionId == vm.dshSessionIdOf(currentId)
    }

    // 顶栏小字：工作区短名 · Agent 预设显示名（§5.10；数据源 = 当前会话，未打开回退全局默认）
    val contextLine = remember(currentSession, defaultWorkspacePath, defaultPreset, presetCatalog) {
        buildString {
            append(
                topbarWorkspaceShortName(
                    currentSession?.workspacePath ?: defaultWorkspacePath,
                    context.filesDir.absolutePath,
                )
            )
            append(" · ")
            append(presetDisplayName(currentSession?.presetId ?: defaultPreset.takeIf { it.isNotBlank() }, presetCatalog))
        }
    }

    // 打开右侧看板 / 切换会话时刷新调用量（流结束与 DSH Running 已在 ViewModel 内刷新）
    LaunchedEffect(currentId, dashboardOpen) {
        if (dashboardOpen) vm.refreshUsageStats()
    }

    // 发送新消息（流式开始）：回到底部开始跟随；
    // 流式结束不再强制回底——尊重用户上滑看历史的脱离状态
    LaunchedEffect(streaming != null) {
        if (streaming != null) {
            listState.scrollToItem(0)
        }
    }

    // 返回键：Compose BackHandler 后注册者先收到，故左抽屉在前、右面板在后 → 先关右面板再关左抽屉
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = dashboardOpen) { dashboardOpen = false }

    // 顶栏显示当前会话标题（没有打开会话时回退为“聊天”）
    val currentTitle = sessions.firstOrNull { it.id == currentId }?.title ?: "聊天"

    // 顶栏标题点击重命名
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // 抽屉打开时给「底图 + 聊天内容」一起加毛玻璃模糊（API < 31 自动退化为不模糊）。
    // 注意这里必须用 targetValue 而不是 isOpen：isOpen 等价于 currentValue == Open，
    // 要等抽屉动画完全结束才变 true，会导致左侧模糊「等抽屉完全出来才开始处理」；
    // targetValue 在调用 open()/close() 或松手 settle() 决定方向的那一刻就更新，
    // 让模糊和抽屉滑入/滑出动画并行跟随（与右侧 dashboardOpen 的即时布尔行为一致，喵~）。
    val drawerOpen = drawerState.targetValue == DrawerValue.Open
    val blurRadius by animateDpAsState(if (drawerOpen || dashboardOpen) 8.dp else 0.dp)

    // 唤出输入法时底图放大（模拟导航栏退场时的视觉张力），收起时缩回原尺寸。
    // 缩放进度由实时 IME 高度驱动，和导航栏/输入栏完全同步，不会慢一拍。
    val bgScale = 1f + 0.1f * imeZoom

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        // 浅遮罩：抽屉只占 ~85% 宽度，右侧聊天页仍然可见
        scrimColor = Color.Black.copy(alpha = 0.25f),
        drawerContent = {
            // 会话抽屉也只垫内容区，避免底部被导航栏盖住；底图层仍保持全屏。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            ) {
                SessionDrawer(
                    sessions = sessions,
                    currentId = currentId,
                    drawerOpen = drawerOpen,
                    onOpen = { id -> vm.openSession(id); scope.launch { drawerState.close() } },
                    onNew = { vm.newSession(); scope.launch { drawerState.close() } },
                    onDelete = vm::deleteSession,
                    onDeleteMany = vm::deleteSessions,
                    onRename = vm::renameSession,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 底图层：全屏铺满，不受导航栏高度影响（导航栏浮在上层即可）
            // 唤出输入法时放大（bgScale），键盘收起时缩回，底图始终铺满整个区域。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius)
                    .scale(bgScale),
            ) {
                ChatBackgroundLayer(chatBackground)
            }
            // 透明 Scaffold：仅内容区垫底部占位，底图层不会被压缩，
            // 导航栏消失/出现时底图大小不变（不会先变大再缩回去）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius)
                    .padding(bottom = bottomPadding),
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            // 两行标题（§5.10）：上方小字 = 工作区短名 · Agent 预设名（不可点击），
                            // 下方大字 = 会话标题（重命名点击绑在大字上不动）；高度 68dp 容纳两行
                            modifier = Modifier.height(68.dp),
                            title = {
                                Column {
                                    Text(
                                        text = contextLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = currentTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = if (currentId != null) {
                                            Modifier.clickable {
                                                renameText = currentTitle
                                                showRenameDialog = true
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "会话管理")
                                }
                            },
                            actions = {
                                IconButton(onClick = { dashboardOpen = true }) {
                                    Icon(Icons.Outlined.Dashboard, contentDescription = "功能看板")
                                }
                                IconButton(onClick = vm::newSession) {
                                    Icon(Icons.Filled.Add, contentDescription = "新会话")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            ),
                        )
                    },
                    bottomBar = {
                        ChatInputArea(
                            input = input,
                            onInputChange = { input = it },
                            attachments = attachments,
                            onPickAttachment = { att ->
                                input = if (input.isBlank()) "[${att.refId}]" else "$input [${att.refId}]"
                            },
                            onRemoveAttachment = { att ->
                                attachments = attachments.filterNot { it.refId == att.refId }
                                input = input.replace("[${att.refId}]", "").replace(Regex("\\s+"), " ").trim()
                            },
                            isGenerating = isGenerating,
                            pendingCount = pendingCount,
                            reasoningEffort = reasoningEffort,
                            webSearchEnabled = webSearchEnabled,
                            attachedMode = attachedMode,
                            hasSession = currentId != null,
                            onSend = {
                                vm.sendMessage(input, attachments)
                                input = ""
                                attachments = emptyList()
                            },
                            onStop = vm::stopGenerating,
                            onSelectReasoningEffort = vm::selectReasoningEffort,
                            onToggleWebSearch = vm::toggleWebSearch,
                            onPickFile = { filePicker.launch("*/*") },
                            onAttachPlan = vm::attachPlan,
                            onAttachGoal = vm::attachGoal,
                            onDetachAttachedMode = {
                                // 单槽位：按当前模式走对应的关闭命令
                                when (attachedMode) {
                                    is AttachedMode.Plan -> vm.detachPlan()
                                    is AttachedMode.Goal -> vm.detachGoal()
                                    null -> Unit
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            // 和左抽屉一致：在聊天内容区任意位置向左滑即可打开功能看板。
                            // 注意这里必须用“只观察不消费”的手势，否则会抢走左侧 ModalNavigationDrawer 的滑动手势。
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var totalX = 0f
                                    var opened = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        totalX += change.positionChange().x
                                        if (!opened && totalX <= -viewConfiguration.touchSlop) {
                                            opened = true
                                            dashboardOpen = true
                                        }
                                    }
                                }
                            },
                    ) {
                        // 上方悬浮栏（§5.5）：todo / subagent 两态；两态都无数据 → 整条不渲染
                        ChatStatusBar(todos = todoState, subagentRuns = subagentRuns)
                        Box(modifier = Modifier.weight(1f)) {
                        if (messages.isEmpty() && streaming == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmptyState(
                                    icon = Icons.Outlined.AutoAwesome,
                                    title = "和喵喵老师聊聊吧～",
                                    description = "左上角管理会话 · 右上角新建",
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                reverseLayout = true,
                            ) {
                                // 过滤掉正在流式的 DB 行（节流落库会产生部分内容），避免与实时气泡同屏重复渲染
                                val visible = messages.filterNot { it.id == streaming?.messageId }
                                // reverseLayout 下 index 0 在屏幕底部：
                                // 先放实时流式气泡（新内容），再放历史消息的倒序（越旧越往上）。
                                streaming?.let { s ->
                                    item(key = "streaming-${s.messageId}") {
                                        AssistantBody(
                                            segments = displayedStreamingSegments ?: s.segments,
                                            status = MessageStatus.STREAMING,
                                            pendingQuestion = pendingQuestionForSession,
                                            interactiveQuestionCallId = latestQuestionCallId,
                                            onAnswerQuestion = vm::answerQuestion,
                                            onCancelQuestion = vm::cancelQuestion,
                                        )
                                    }
                                }
                                items(visible.asReversed(), key = { it.id }) { msg ->
                                    MessageRow(
                                        msg = msg,
                                        pendingQuestion = pendingQuestionForSession,
                                        interactiveQuestionCallId = latestQuestionCallId,
                                        onAnswerQuestion = vm::answerQuestion,
                                        onCancelQuestion = vm::cancelQuestion,
                                    )
                                }
                            }
                        }
                        // 上滑脱离跟随后出现「回到底部」：点击回到最新内容并恢复跟随
                        if (!isAtBottom) {
                            SmallFloatingActionButton(
                                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            ) {
                                Icon(
                                    Icons.Filled.ArrowDownward,
                                    contentDescription = "回到底部",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }

        // 右侧功能看板 overlay（放在 ModalNavigationDrawer 外层，盖住聊天内容）
        // 同样只垫内容区，避免面板底部被底部导航栏盖住；底图层仍保持全屏。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding),
        ) {
            DashboardDrawer(
                open = dashboardOpen,
                feature = dashboardFeature,
                onFeatureChange = vm::selectDashboardFeature,
                onClose = { dashboardOpen = false },
                modelPanel = {
                    ModelManagePanel(
                        currentProvider = currentProvider,
                        providers = providers,
                        llmModel = llmModel,
                        availableModels = availableModels,
                        onSelectProvider = vm::selectProvider,
                        onSelectModel = vm::selectModel,
                    )
                },
                filesPanel = {
                    QuickFilesPanel(
                        vm = quickVm,
                        panelOpen = dashboardOpen && dashboardFeature == DashboardFeature.FILES,
                        attachedPaths = attachedPaths,
                        onToggleAttach = onToggleAttach,
                    )
                },
                statsPanel = {
                    UsageStatsPanel(
                        stats = sessionUsageStats,
                        loading = false,
                        onRefresh = vm::refreshUsageStats,
                    )
                },
                workspaceSettingsPanel = { WorkspaceSettingsPanel(vm) },
            )
        }

    // 顶栏标题点击 → 重命名当前会话（未打开会话时标题不可点，不会弹）
    if (showRenameDialog && currentId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameSession(currentId, renameText)
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
        )
    }
    }
}

/** 非快照状态的持有者：组合期间同步记录「贴底时的最新流式分段」，避免写 State 引发额外重组 */
private class StreamingSegmentsSnapshot {
    var segments: List<Segment>? = null
}

/** Agent 预设显示名（PresetCatalog 缓存查 name，查不到显示 id，null → 「默认」；§5.10） */
internal fun presetDisplayName(presetId: String?, catalog: List<com.meow.academy.data.model.PresetEntry>): String {
    if (presetId.isNullOrBlank()) return "默认"
    return catalog.firstOrNull { it.id == presetId }?.name ?: presetId
}

/**
 * 顶栏小字用的工作区短名（§5.10，规则同 §5.7①）：
 * `<filesDir>/workspace` → 「workspace」、`<filesDir>` 本身 → 「files」、其余取最后一段文件夹名；
 * null（旧数据未写工作区）→ 按历史唯一工作区回退「workspace」。本文件私有实现，不与看板共用避免耦合。
 */
private fun topbarWorkspaceShortName(path: String?, filesDirPath: String): String {
    val normalized = (path ?: "").trimEnd('/')
    val root = filesDirPath.trimEnd('/')
    return when {
        normalized.isEmpty() -> "workspace"
        normalized == root -> "files"
        normalized == "$root/workspace" -> "workspace"
        else -> normalized.substringAfterLast('/')
    }
}
