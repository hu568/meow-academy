package com.meow.academy.ui.chat

import android.text.method.LinkMovementMethod
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.data.chat.SessionEntity
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.launch

/** DeepSeek 可切换模型（输入栏工具栏下拉） */
private val DEEPSEEK_MODELS = listOf("deepseek-v4-flash", "deepseek-chat", "deepseek-reasoner")

/** 思考强度档位（llm-deepseek 合法值域 off/high/max） */
private val REASONING_EFFORTS = listOf("off", "high", "max")

private fun modelLabel(model: String): String = when (model) {
    "deepseek-v4-flash" -> "v4-flash"
    "deepseek-chat" -> "chat"
    "deepseek-reasoner" -> "reasoner"
    else -> model
}

private fun effortLabel(effort: String): String = when (effort) {
    "off" -> "关闭思考"
    "high" -> "高"
    "max" -> "最强"
    else -> effort
}

/**
 * 💬 聊天页（Chatbox 风格）：单页详情 + 左侧会话抽屉 + 顶栏新会话 + 输入栏工具栏。
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

    // 新消息/流式增量 → 自动滚到底部（scrollToItem 无动画，避免流式时与内容追加竞争导致抽搐）
    LaunchedEffect(messages.size, streaming?.content?.length, streaming?.thinking?.length) {
        if (messages.isNotEmpty() || streaming != null) {
            listState.scrollToItem(Int.MAX_VALUE)
        }
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
                    llmModel = llmModel,
                    reasoningEffort = reasoningEffort,
                    webSearchEnabled = webSearchEnabled,
                    onSend = { vm.sendMessage(input); input = "" },
                    onStop = vm::stopGenerating,
                    onSelectModel = vm::selectModel,
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 过滤掉正在流式的 DB 行（节流落库会产生部分内容），避免与实时气泡同屏重复渲染
                    val visible = messages.filterNot { it.id == streaming?.messageId }
                    items(visible, key = { it.id }) { msg ->
                        MessageRow(msg)
                    }
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
    }
}

// ─────────────────── 会话抽屉 ───────────────────

@Composable
private fun SessionDrawer(
    sessions: List<SessionEntity>,
    currentId: Long?,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (SessionEntity) -> Unit,
    onRename: (Long, String) -> Unit,
) {
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }

    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("会话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "新建会话") }
        }
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(sessions, key = { it.id }) { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (session.id == currentId) MaterialTheme.colorScheme.surfaceVariant
                                else Color.Transparent,
                            )
                            .clickable { onOpen(session.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
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
                        IconButton(onClick = { renaming = session }) {
                            Icon(Icons.Filled.Edit, contentDescription = "重命名", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = session }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
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
            text = { Text("确定删除「${session.title}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { onDelete(session); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
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
                msg.content.isNotBlank() && msg.status == MessageStatus.STREAMING -> Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyLarge,
                )
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

// ─────────────────── 输入栏 + 工具栏 ───────────────────

@Composable
private fun ChatInputArea(
    input: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    llmModel: String,
    reasoningEffort: String,
    webSearchEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("和喵喵老师聊聊…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
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
        ChatToolbar(
            llmModel = llmModel,
            reasoningEffort = reasoningEffort,
            webSearchEnabled = webSearchEnabled,
            onSelectModel = onSelectModel,
            onSelectReasoningEffort = onSelectReasoningEffort,
            onToggleWebSearch = onToggleWebSearch,
            onPickFile = onPickFile,
        )
    }
}

@Composable
private fun ChatToolbar(
    llmModel: String,
    reasoningEffort: String,
    webSearchEnabled: Boolean,
    onSelectModel: (String) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickFile: () -> Unit,
) {
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            AssistChip(onClick = { modelMenu = true }, label = { Text(modelLabel(llmModel), fontSize = 12.sp) })
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                DEEPSEEK_MODELS.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = { onSelectModel(m); modelMenu = false },
                    )
                }
            }
        }
        Box {
            AssistChip(
                onClick = { effortMenu = true },
                label = { Text("思考·${effortLabel(reasoningEffort)}", fontSize = 12.sp) },
            )
            DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                REASONING_EFFORTS.forEach { e ->
                    DropdownMenuItem(
                        text = { Text(effortLabel(e)) },
                        onClick = { onSelectReasoningEffort(e); effortMenu = false },
                    )
                }
            }
        }
        AssistChip(
            onClick = { onToggleWebSearch(!webSearchEnabled) },
            label = { Text(if (webSearchEnabled) "联网·开" else "联网·关", fontSize = 12.sp) },
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPickFile) {
            Icon(Icons.Filled.AttachFile, contentDescription = "上传文件")
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
