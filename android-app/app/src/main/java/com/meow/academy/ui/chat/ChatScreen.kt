package com.meow.academy.ui.chat

import android.text.method.LinkMovementMethod
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * 💬 聊天页（M2.4）：会话列表 ⇄ 会话详情，流式对话 + Markdown 渲染 + 工具卡片。
 */
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()

    if (currentId == null) {
        SessionListView(sessions = sessions, onNew = vm::newSession, onOpen = vm::openSession)
    } else {
        ChatDetailView(vm = vm, onBack = vm::closeSession)
    }
}

// ─────────────────── 会话列表 ───────────────────

@Composable
private fun SessionListView(
    sessions: List<com.meow.academy.data.chat.SessionEntity>,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = "💬 聊天",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有会话喵～\n点右下角 + 开始聊天",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sessions, key = { it.id }) { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { onOpen(session.id) },
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = session.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(session.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────── 会话详情 ───────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailView(vm: ChatViewModel, onBack: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新消息/流式增量 → 自动滚到底部
    LaunchedEffect(messages.size, streaming?.content?.length, streaming?.thinking?.length) {
        if (messages.isNotEmpty() || streaming != null) {
            listState.animateScrollToItem(Int.MAX_VALUE)
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("聊天") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                input = input,
                onInputChange = { input = it },
                isGenerating = isGenerating,
                onSend = { vm.sendMessage(input); input = "" },
                onStop = vm::stopGenerating,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 过滤掉正在流式的 DB 行（节流落库会产生部分内容），避免与实时气泡同屏重复渲染
            val visible = messages.filterNot { it.id == streaming?.messageId }
            items(visible, key = { it.id }) { msg ->
                MessageRow(msg)
            }
            // 流式增量（正在生成的消息，实时渲染）
            streaming?.let { s ->
                item(key = "streaming") {
                    val entity = MessageEntity(
                        id = -1,
                        sessionId = -1,
                        role = MessageRole.ASSISTANT,
                        content = s.content,
                        thinking = s.thinking,
                        toolCallsJson = null,
                        status = MessageStatus.STREAMING,
                    )
                    Column {
                        s.thinking.takeIf { it.isNotBlank() }?.let { ThinkingCard(it) }
                        s.toolCalls.forEach { ToolCard(it) }
                        AssistantBubble(entity)
                    }
                }
            }
        }
    }
}

// ─────────────────── 消息气泡 ───────────────────

@Composable
private fun MessageRow(msg: MessageEntity) {
    when (msg.role) {
        MessageRole.USER -> UserBubble(msg.content)
        MessageRole.ASSISTANT -> {
            Column {
                msg.thinking.takeIf { it.isNotBlank() }?.let { ThinkingCard(it) }
                msg.toolCallsJson?.let { json ->
                    parseToolCalls(json).forEach { ToolCard(it) }
                }
                AssistantBubble(msg)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantBubble(msg: MessageEntity) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            when {
                msg.content.isBlank() && msg.status == MessageStatus.STREAMING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("思考中…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                msg.content.isNotBlank() -> MarkdownText(msg.content)
                else -> Text(
                    msg.status.takeIf { it == MessageStatus.ERROR }?.let { "⚠️ 出错" } ?: "（空回复）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** thinking 折叠卡片 */
@Composable
private fun ThinkingCard(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = if (expanded) "🧠 思考过程（点击收起）" else "🧠 思考过程（点击展开）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 工具调用卡片 */
@Composable
private fun ToolCard(tool: ChatViewModel.ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (tool.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛠 ${tool.name}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                if (tool.arguments.isNotBlank()) {
                    Text(
                        "参数：${tool.arguments}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (tool.result.isNotBlank()) {
                    Text(
                        "结果：${tool.result.take(2000)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (tool.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────── 输入栏 ───────────────────

@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text("和喵喵老师聊聊…") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            // 键盘弹起时发送按钮可能被顶到状态栏后面点不到，支持键盘 Send 键直接发送
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) onSend() }),
        )
        Spacer(Modifier.width(8.dp))
        if (isGenerating) {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "停止生成", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

// ─────────────────── Markdown 渲染（Markwon） ───────────────────

/**
 * Markdown 渲染：Markwon（标题/列表/表格/代码块/引用/链接/图片），
 * 经 AndroidView 嵌入 Compose。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                textSize = 15f
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view -> markwon.setMarkdown(view, markdown) },
    )
}

// ─────────────────── 工具调用 JSON 解析 ───────────────────

private fun parseToolCalls(json: String): List<ChatViewModel.ToolCallInfo> {
    return runCatching {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            ChatViewModel.ToolCallInfo(
                id = obj["id"]?.toString() ?: "",
                name = obj["name"]?.toString() ?: "unknown",
                arguments = obj["arguments"]?.toString() ?: "",
                result = obj["result"]?.toString() ?: "",
                isError = obj["isError"]?.toString()?.toBoolean() ?: false,
            )
        }
    }.getOrDefault(emptyList())
}
