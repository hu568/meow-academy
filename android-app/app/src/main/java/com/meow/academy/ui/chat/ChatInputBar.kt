package com.meow.academy.ui.chat

/**
 * 聊天输入栏 + 工具栏组件。
 * 输入框/发送/停止 + provider/模型/思考强度下拉 + 联网开关 + 上传文件；
 * 从 ChatScreen.kt 原子拆出。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.academy.rpc.LlmProviderInfo

/** DeepSeek 可切换模型（输入栏工具栏下拉；deepseek-chat/reasoner 已弃用，仅 v4 系列有效） */
private val DEEPSEEK_MODELS = listOf("deepseek-v4-flash", "deepseek-v4-pro")

/** 思考强度档位（llm-deepseek 合法值域 off/high/max） */
private val REASONING_EFFORTS = listOf("off", "high", "max")

private fun modelLabel(model: String): String = when (model) {
    "deepseek-v4-flash" -> "v4-flash"
    "deepseek-v4-pro" -> "v4-pro"
    else -> model
}

private fun providerLabel(provider: String, providers: List<LlmProviderInfo>): String =
    providers.firstOrNull { it.provider == provider }?.displayName ?: provider

/** 首字圆标头像（provider 用主色容器、模型用次要色容器区分；工具栏芯片/下拉菜单共用） */
@Composable
private fun LetterAvatar(
    name: String,
    size: Dp = 20.dp,
    fontSize: TextUnit = 11.sp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = Modifier.size(size).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            fontSize = fontSize,
            color = content,
        )
    }
}

/** 模型首字圆标（次要色容器，与 provider 头像区分） */
@Composable
private fun ModelAvatar(name: String, size: Dp = 20.dp, fontSize: TextUnit = 11.sp) =
    LetterAvatar(
        name = name,
        size = size,
        fontSize = fontSize,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
    )

private fun effortLabel(effort: String): String = when (effort) {
    "off" -> "关闭思考"
    "high" -> "高"
    "max" -> "最强"
    else -> effort
}

/** 输入栏：文本框 + 发送/停止 + 下方工具栏 */
@Composable
fun ChatInputArea(
    input: String,
    onInputChange: (String) -> Unit,
    isGenerating: Boolean,
    pendingCount: Int,
    llmModel: String,
    reasoningEffort: String,
    webSearchEnabled: Boolean,
    providers: List<LlmProviderInfo>,
    availableModels: List<String>,
    currentProvider: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
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
        // 待发送队列提示（DSH 未就绪/正在生成时入队的消息，就绪后自动发出）
        if (pendingCount > 0) {
            Text(
                "⏳ $pendingCount 条消息待发送，DSH 就绪后自动发出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
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
            providers = providers,
            availableModels = availableModels,
            currentProvider = currentProvider,
            onSelectModel = onSelectModel,
            onSelectProvider = onSelectProvider,
            onSelectReasoningEffort = onSelectReasoningEffort,
            onToggleWebSearch = onToggleWebSearch,
            onPickFile = onPickFile,
        )
    }
}

/** 工具栏：provider / 模型 / 思考强度下拉 + 联网开关 + 上传文件 */
@Composable
fun ChatToolbar(
    llmModel: String,
    reasoningEffort: String,
    webSearchEnabled: Boolean,
    providers: List<LlmProviderInfo>,
    availableModels: List<String>,
    currentProvider: String,
    onSelectModel: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickFile: () -> Unit,
) {
    var modelMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            AssistChip(
                onClick = { providerMenu = true },
                // 收起时只显示首字图标，省出整行空间（展开菜单里才有名字）
                label = { LetterAvatar(providerLabel(currentProvider, providers)) },
            )
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                providers.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LetterAvatar(p.displayName, size = 24.dp, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(p.displayName)
                            }
                        },
                        onClick = { onSelectProvider(p.provider); providerMenu = false },
                    )
                }
            }
        }
        Box {
            AssistChip(
                onClick = { modelMenu = true },
                label = { ModelAvatar(modelLabel(llmModel)) },
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                availableModels.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ModelAvatar(modelLabel(m), size = 24.dp, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(m)
                            }
                        },
                        onClick = { onSelectModel(m); modelMenu = false },
                    )
                }
            }
        }
        Box {
            AssistChip(
                onClick = { effortMenu = true },
                label = { Text("思考·" + effortLabel(reasoningEffort), fontSize = 12.sp) },
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
