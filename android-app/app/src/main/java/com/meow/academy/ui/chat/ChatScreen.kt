package com.meow.academy.ui.chat

/**
 * 聊天页骨架（原子拆分后的「结构」层）。
 * 只负责组装：抽屉（SessionDrawer.kt）+ Scaffold + 消息列表（MessageBubbles.kt）+ 输入栏（ChatInputBar.kt）；
 * 状态与流式逻辑在 ChatViewModel.kt，模型/序列化在 ChatSegment.kt / ChatSegmentJson.kt。
 */

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.data.chat.SessionEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

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
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 上传文件：选文本文件 → 读内容附加到输入框（二进制/图片暂不支持）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null && text.isNotBlank()) {
                input = if (input.isBlank()) "[附件内容]\n$text" else "$input\n\n[附件内容]\n$text"
            }
        }
    }

    // 贴底跟随：仅当用户处于底部时才自动滚到底；用户上滑后暂停跟随，滚回底部恢复。
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .distinctUntilChanged()
            .collect { canScroll -> atBottom = !canScroll }
    }
    // 新消息/流式增量 → 贴底时自动跟随（scrollToItem 无动画，避免流式时与内容追加竞争导致抽搐）
    LaunchedEffect(messages.size, streaming?.segments) {
        if (atBottom && (messages.isNotEmpty() || streaming != null)) {
            listState.scrollToItem(Int.MAX_VALUE)
        }
    }

    // 抽屉打开时，系统返回键关闭抽屉（而不是退出 App）
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("聊天") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话管理")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newSession) {
                            Icon(Icons.Filled.Add, contentDescription = "新会话")
                        }
                    },
                )
            },
            bottomBar = {
                ChatInputArea(
                    input = input,
                    onInputChange = { input = it },
                    isGenerating = isGenerating,
                    pendingCount = pendingCount,
                    llmModel = llmModel,
                    reasoningEffort = reasoningEffort,
                    webSearchEnabled = webSearchEnabled,
                    providers = providers,
                    availableModels = availableModels,
                    currentProvider = currentProvider,
                    onSend = { vm.sendMessage(input); input = "" },
                    onStop = vm::stopGenerating,
                    onSelectModel = vm::selectModel,
                    onSelectProvider = vm::selectProvider,
                    onSelectReasoningEffort = vm::selectReasoningEffort,
                    onToggleWebSearch = vm::toggleWebSearch,
                    onPickFile = { filePicker.launch("*/*") },
                )
            },
        ) { padding ->
            if (messages.isEmpty() && streaming == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "开始和喵喵老师聊聊吧～\n左上角管理会话，右上角新建",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 过滤掉正在流式的 DB 行（节流落库会产生部分内容），避免与实时气泡同屏重复渲染
                    val visible = messages.filterNot { it.id == streaming?.messageId }
                    items(visible, key = { it.id }) { msg ->
                        MessageRow(msg)
                    }
                    streaming?.let { s ->
                        item(key = "streaming") {
                            AssistantBody(
                                segments = s.segments,
                                status = MessageStatus.STREAMING,
                            )
                        }
                    }
                }
            }
        }
    }
}
