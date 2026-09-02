package com.meow.academy.ui.chat

/**
 * 聊天页薄壳（plan-chatscreen-refactor）：只做状态收集 + 附件副作用回调 + 骨架装配。
 * 分片：顶栏 ChatTopBar / 消息列表 ChatMessageList / 右侧看板 ChatDashboardOverlay；
 * 状态与流式逻辑在 ChatViewModel.kt（门面 + 控制器），模型/序列化在 ChatSegment.kt / ChatSegmentJson.kt。
 */

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.ui.files.FilesViewModel
import kotlinx.coroutines.launch

/** 💬 聊天页（Chatbox 风格）：单页详情 + 左侧会话抽屉 + 顶栏新会话 + 输入栏工具栏。骨架只负责组装 */
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel(), bottomPadding: Dp = 0.dp, imeZoom: Float = 0f) {
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    ChatDetailView(vm = vm, sessions = sessions, currentId = currentId, bottomPadding = bottomPadding, imeZoom = imeZoom)
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
    val supportedEfforts by vm.supportedEfforts.collectAsState()
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
    val personaCatalog by vm.personaCatalog.collectAsState()
    val sessionFilter by vm.sessionFilter.collectAsState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val attachedPaths = attachments.map { it.path }.toSet()
    // 右侧功能看板（快捷文件用独立 FilesViewModel，key 固定避免与文件页互踩）
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

    // 上传文件：去重复制到 uploads/ → 加到附件预览，不自动引用；点预览才插 [引用标记]（喵~）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val uploadDir = RuntimeExtractor.workspaceUploadsDir(context).absolutePath
            val result = repository.importDeduplicated(uri, uploadDir)
            if (result != null) {
                // 去重判定：已有同路径 → 静默跳过；无 → 记最近使用 + 追加
                val existing = ChatAttachmentLogic.existingByPath(attachments, result.file.absolutePath)
                if (existing == null) {
                    quickVm.recordRecent(result.file.absolutePath) // 上传即使用（喵~）
                    val refId = nextAttachmentRefId(attachments, result.file.name)
                    attachments = attachments + ChatAttachmentLogic.buildNew(refId, result.file.name, result.file.absolutePath)
                }
            } else {
                snackbarHostState.showSnackbar("上传文件失败")
            }
        }
    }

    // 快速附加（右侧看板「快捷文件」）：点文件加入附件再点取消；无则记最近使用（置顶喵~），有则只删不记
    val onToggleAttach: (FileEntry) -> Unit = { file ->
        val existing = ChatAttachmentLogic.existingByPath(attachments, file.path)
        if (existing == null) {
            quickVm.recordRecent(file.path)
        }
        val (newAttachments, newInput) = ChatAttachmentLogic.toggleAttach(file, attachments, input)
        attachments = newAttachments
        input = newInput
    }

    // 打开会话：默认回到底部（跟随）
    LaunchedEffect(currentId) { listState.scrollToItem(0) }

    // 自动打开最近会话：currentId 为空且已有会话 → 打开 updatedAt 最新一条；删除后归空同样落位（喵~）
    LaunchedEffect(sessions, currentId) {
        if (currentId == null && sessions.isNotEmpty()) {
            sessions.firstOrNull()?.let { vm.openSession(it.id) }
        }
    }

    // 打开右侧看板 / 切换会话时刷新调用量（流结束与 DSH Running 已在 ViewModel 内刷新）
    LaunchedEffect(currentId, dashboardOpen) { if (dashboardOpen) vm.refreshUsageStats() }

    // 发送新消息（流式开始）回到底部跟随；流式结束不强制回底——尊重上滑看历史的脱离状态
    LaunchedEffect(streaming != null) { if (streaming != null) listState.scrollToItem(0) }

    // 返回键：Compose BackHandler 后注册者先收到，故左抽屉在前、右面板在后 → 先关右面板再关左抽屉
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = dashboardOpen) { dashboardOpen = false }

    val currentTitle = sessions.firstOrNull { it.id == currentId }?.title ?: "聊天"
    // 当前会话角色名（plan-memory-execution §3.4）：角色开关 OFF / 未绑定 → 空串（顶栏不追加该段）
    val personaName = if (currentSession?.personaEnabled == true) {
        personaDisplayName(currentSession?.personaId, personaCatalog)
    } else {
        ""
    }

    // 抽屉打开时给「底图 + 聊天内容」一起加毛玻璃模糊（API < 31 自动退化不模糊）。
    // 用 targetValue 而非 isOpen（isOpen 要等动画结束才变 true，模糊会慢半拍）——模糊与滑入/滑出并行（喵~）
    val drawerOpen = drawerState.targetValue == DrawerValue.Open
    val blurRadius by animateDpAsState(if (drawerOpen || dashboardOpen) 8.dp else 0.dp)

    // 唤出输入法时底图放大（模拟导航栏退场张力），缩放进度由实时 IME 高度驱动、与键盘完全同步
    val bgScale = 1f + 0.1f * imeZoom

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.25f),
        drawerContent = {
            // 会话抽屉也只垫内容区，避免底部被导航栏盖住；底图层仍保持全屏。
            Box(Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
                SessionDrawer(
                    sessions = sessions,
                    currentId = currentId,
                    drawerOpen = drawerOpen,
                    sessionFilter = sessionFilter,
                    defaultWorkspacePath = defaultWorkspacePath,
                    presetCatalog = presetCatalog,
                    personaCatalog = personaCatalog,
                    onFilterChange = vm::setSessionFilter,
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
            // 底图层：全屏铺满，不受导航栏高度影响（导航栏浮在上层即可）；唤出输入法时放大
            Box(Modifier.fillMaxSize().blur(blurRadius).scale(bgScale)) {
                ChatBackgroundLayer(chatBackground)
            }
            // 透明 Scaffold：仅内容区垫底部占位，底图层不会被压缩，导航栏消失/出现时底图大小不变
            Box(Modifier.fillMaxSize().blur(blurRadius).padding(bottom = bottomPadding)) {
                Scaffold(
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        ChatTopBar(
                            currentId = currentId,
                            currentTitle = currentTitle,
                            workspacePath = currentSession?.workspacePath,
                            defaultWorkspacePath = defaultWorkspacePath,
                            currentPresetId = currentSession?.presetId,
                            defaultPreset = defaultPreset,
                            presetCatalog = presetCatalog,
                            personaName = personaName,
                            filesDirPath = context.filesDir.absolutePath,
                            onRename = vm::renameSession,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenDashboard = { dashboardOpen = true },
                            onNewSession = vm::newSession,
                        )
                    },
                    bottomBar = {
                        ChatInputArea(
                            input = input,
                            onInputChange = { input = it },
                            attachments = attachments,
                            onPickAttachment = { att -> input = ChatAttachmentLogic.insertRef(input, att.refId) },
                            onRemoveAttachment = { att ->
                                val (newAtts, newInput) = ChatAttachmentLogic.removeRef(att.refId, attachments, input)
                                attachments = newAtts; input = newInput
                            },
                            isGenerating = isGenerating,
                            pendingCount = pendingCount,
                            reasoningEffort = reasoningEffort,
                            supportedEfforts = supportedEfforts,
                            webSearchEnabled = webSearchEnabled,
                            attachedMode = attachedMode,
                            hasSession = currentId != null,
                            onSend = {
                                vm.sendMessage(input, attachments); input = ""; attachments = emptyList()
                            },
                            onStop = vm::stopGenerating,
                            onSelectReasoningEffort = vm::selectReasoningEffort,
                            onToggleWebSearch = vm::toggleWebSearch,
                            onPickFile = { filePicker.launch("*/*") },
                            onAttachPlan = vm::attachPlan,
                            onAttachGoal = vm::attachGoal,
                            onDetachAttachedMode = {
                                when (attachedMode) {
                                    is AttachedMode.Plan -> vm.detachPlan()
                                    is AttachedMode.Goal -> vm.detachGoal()
                                    null -> Unit
                                }
                            },
                        )
                    },
                ) { padding ->
                    // 消息列表分片：自管快照/手势/滚动跟随/问答卡推导/回底 FAB/悬浮栏。薄壳只传状态与回调。
                    ChatMessageList(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        messages = messages,
                        streaming = streaming,
                        currentId = currentId,
                        pendingQuestion = pendingQuestion,
                        dshSessionIdOf = vm::dshSessionIdOf,
                        onAnswerQuestion = vm::answerQuestion,
                        onCancelQuestion = vm::cancelQuestion,
                        todos = todoState,
                        subagentRuns = subagentRuns,
                        onOpenDashboard = { dashboardOpen = true },
                        listState = listState,
                    )
                }
            }
        }
    }

        // 右侧功能看板 overlay（放 ModalNavigationDrawer 外层盖住聊天内容；只垫内容区，底图层仍全屏）
        ChatDashboardOverlay(
            open = dashboardOpen,
            feature = dashboardFeature,
            bottomPadding = bottomPadding,
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
}
