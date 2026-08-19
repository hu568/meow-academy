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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.meow.academy.ui.components.EmptyState
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

    // 底部锚定：聊天列表使用 reverseLayout，index 0 永远在屏幕底部。
    // 流式气泡放在 index 0，文本变长时底部天然跟随，不需要也不应该每 token 调 scrollToItem，
    // 否则会出现高频抽搐 / 文字重叠。
    // 这里只在切换会话/从空会话出现第一条消息/流式开始时把滚动位置归到底部（避免复用旧会话的滚动位置；
    // 流式开始后文本增长不再需要滚动，reverseLayout 会天然保持底部）。
    LaunchedEffect(currentId, messages.isEmpty(), streaming != null) {
        if (messages.isNotEmpty() || streaming != null) {
            listState.scrollToItem(0)
        }
    }

    // 抽屉打开时，系统返回键关闭抽屉（而不是退出 App）
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 顶栏显示当前会话标题（没有打开会话时回退为“聊天”）
    val currentTitle = sessions.firstOrNull { it.id == currentId }?.title ?: "聊天"

    // 抽屉打开时给「底图 + 聊天内容」一起加毛玻璃模糊（API < 31 自动退化为不模糊）
    val drawerOpen = drawerState.isOpen
    val blurRadius by animateDpAsState(if (drawerOpen) 8.dp else 0.dp)

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
                            EmptyState(
                                icon = Icons.Outlined.AutoAwesome,
                                title = "和喵喵老师聊聊吧～",
                                description = "左上角管理会话 · 右上角新建",
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
                            reverseLayout = true,
                        ) {
                            // 过滤掉正在流式的 DB 行（节流落库会产生部分内容），避免与实时气泡同屏重复渲染
                            val visible = messages.filterNot { it.id == streaming?.messageId }
                            // reverseLayout 下 index 0 在屏幕底部：
                            // 先放实时流式气泡（新内容），再放历史消息的倒序（越旧越往上）。
                            streaming?.let { s ->
                                item(key = "streaming-${s.messageId}") {
                                    AssistantBody(
                                        segments = s.segments,
                                        status = MessageStatus.STREAMING,
                                    )
                                }
                            }
                            items(visible.asReversed(), key = { it.id }) { msg ->
                                MessageRow(msg)
                            }
                        }
                    }
                }
            }
        }
    }
}
