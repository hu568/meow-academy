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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    ChatDetailView(vm = vm, sessions = sessions, currentId = currentId)
}

// ─────────────────── 会话详情 ───────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailView(
    vm: ChatViewModel,
    sessions: List<SessionEntity>,
    currentId: Long?,
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
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val attachedPaths = attachments.map { it.path }.toSet()
    // 右侧功能看板：开合 + 当前功能页（快捷文件用独立 FilesViewModel，key 固定避免与文件页互踩）
    var dashboardOpen by rememberSaveable { mutableStateOf(false) }
    var dashboardFeature by rememberSaveable { mutableStateOf(DashboardFeature.MODELS) }
    val quickVm: FilesViewModel = viewModel(key = "quickFiles")
    val sessionUsageStats by vm.sessionUsageStats.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) { FileRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    // 抽屉打开时给「底图 + 聊天内容」一起加毛玻璃模糊（API < 31 自动退化为不模糊）
    val drawerOpen = drawerState.isOpen
    val blurRadius by animateDpAsState(if (drawerOpen || dashboardOpen) 8.dp else 0.dp)

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        // 浅遮罩：抽屉只占 ~85% 宽度，右侧聊天页仍然可见
        scrimColor = Color.Black.copy(alpha = 0.25f),
        drawerContent = {
            SessionDrawer(
                sessions = sessions,
                currentId = currentId,
                onOpen = { id -> vm.openSession(id); scope.launch { drawerState.close() } },
                onNew = { vm.newSession(); scope.launch { drawerState.close() } },
                onDelete = vm::deleteSession,
                onRename = vm::renameSession,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 底图层：放在 ModalNavigationDrawer 内容内部，抽屉遮罩/毛玻璃能同时作用于它
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius),
            ) {
                ChatBackgroundLayer(chatBackground)
            }
            // 透明 Scaffold：聊天内容叠在底图上，抽屉打开时同样被模糊
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadius),
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = currentTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                            llmModel = llmModel,
                            reasoningEffort = reasoningEffort,
                            webSearchEnabled = webSearchEnabled,
                            providers = providers,
                            availableModels = availableModels,
                            currentProvider = currentProvider,
                            onSend = {
                                vm.sendMessage(buildMessageWithAttachments(input, attachments))
                                input = ""
                                attachments = emptyList()
                            },
                            onStop = vm::stopGenerating,
                            onSelectModel = vm::selectModel,
                            onSelectProvider = vm::selectProvider,
                            onSelectReasoningEffort = vm::selectReasoningEffort,
                            onToggleWebSearch = vm::toggleWebSearch,
                            onPickFile = { filePicker.launch("*/*") },
                        )
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
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
                                        )
                                    }
                                }
                                items(visible.asReversed(), key = { it.id }) { msg ->
                                    MessageRow(msg)
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

        // 右侧功能看板 overlay（放在 ModalNavigationDrawer 外层，盖住聊天内容）
        DashboardDrawer(
            open = dashboardOpen,
            feature = dashboardFeature,
            onFeatureChange = { dashboardFeature = it },
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
        )
    }
}

/**
 * 生成附件引用 id：按扩展名 + 序号，例如 文件.md → md1、第二个 md → md2；
 * 无扩展名用 file1 / file2。
 */
private fun nextAttachmentRefId(attachments: List<PendingAttachment>, displayName: String): String {
    val ext = displayName.substringAfterLast('.', "").lowercase().ifEmpty { "file" }
    val count = attachments.count { it.refId.startsWith(ext) }
    return "$ext${count + 1}"
}

/**
 * 发送前把输入框里的 `[refId]` 引用替换成 Markdown 文件链接 `[文件名](路径)`。
 * 如果引用标记被用户删掉，把该附件链接追加到消息末尾，避免附件静默丢失（喵~）。
 */
private fun buildMessageWithAttachments(input: String, attachments: List<PendingAttachment>): String {
    var text = input
    attachments.forEach { att ->
        val ref = "[${att.refId}]"
        val link = markdownFileLink(att.displayName, att.path)
        text = if (ref in text) text.replace(ref, link) else "$text\n\n$link"
    }
    return text.trim()
}

/**
 * 把上传文件转成聊天里的 Markdown 链接 `[文件名](路径)`。
 * 路径含空格 / 括号时用 `<…>` 包裹链接目标，保证 Markwon 能完整渲染（喵~）。
 */
private fun markdownFileLink(name: String, path: String): String {
    val target = if (path.any { it.isWhitespace() || it == '(' || it == ')' }) "<$path>" else path
    return "[$name]($target)"
}

/** 非快照状态的持有者：组合期间同步记录「贴底时的最新流式分段」，避免写 State 引发额外重组 */
private class StreamingSegmentsSnapshot {
    var segments: List<Segment>? = null
}
