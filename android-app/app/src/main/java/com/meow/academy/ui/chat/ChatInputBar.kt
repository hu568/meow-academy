package com.meow.academy.ui.chat

/**
 * 聊天输入栏 + 工具栏组件。
 * 输入框/发送/停止 + provider/模型/思考强度下拉 + 联网开关 + 上传文件；
 * 从 ChatScreen.kt 原子拆出。
 */

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.academy.rpc.LlmProviderInfo
import com.meow.academy.ui.settings.ModelAvatar
import com.meow.academy.ui.settings.ProviderAvatar

/**
 * 待发送附件（上传后在输入框上方预览）。
 * 输入框内用 `[refId]` 引用，发送时由 ChatScreen 替换成 `[文件名](路径)` Markdown。
 */
data class PendingAttachment(
    val refId: String,
    val displayName: String,
    val path: String,
)

/** DeepSeek 可切换模型（输入栏工具栏下拉；deepseek-chat/reasoner 已弃用，仅 v4 系列有效） */
private val DEEPSEEK_MODELS = listOf("deepseek-v4-flash", "deepseek-v4-pro")

/** 思考强度档位（llm-deepseek 合法值域 off/high/max） */
private val REASONING_EFFORTS = listOf("off", "high", "max")

/** 输入栏：文本框 + 发送/停止 + 下方工具栏 */
@Composable
fun ChatInputArea(
    input: String,
    onInputChange: (String) -> Unit,
    attachments: List<PendingAttachment> = emptyList(),
    onPickAttachment: (PendingAttachment) -> Unit = {},
    onRemoveAttachment: (PendingAttachment) -> Unit = {},
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
    // 外层只负责 imePadding（键盘顶起），内层才是半透明输入栏（避免背景盖到键盘区）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 半透明：聊天底图透出，形成玻璃输入栏
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
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
            // 待发送附件：输入框上方预览（(文件名) + 可移除），下方分隔线与输入框隔开
            if (attachments.isNotEmpty()) {
                Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    attachments.forEach { att ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPickAttachment(att) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "插入引用 ${att.displayName}",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "(${att.displayName})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp),
                            )
                            IconButton(
                                onClick = { onRemoveAttachment(att) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除附件 ${att.displayName}",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 服务商：圆形按钮内显示该厂商自己的头像/Logo
        Box {
            ToolCircleButton(
                onClick = { providerMenu = true },
                contentDescription = "切换服务商（当前${providerLabel(currentProvider, providers)}）",
                container = Color.Transparent,
                contentColor = Color.Transparent,
            ) {
                ProviderAvatar(currentProvider, providerLabel(currentProvider, providers), size = 40.dp)
            }
            DropdownMenu(
                expanded = providerMenu,
                onDismissRequest = { providerMenu = false },
                // 不抢主窗口焦点，避免输入框失焦导致输入法收起
                properties = PopupProperties(focusable = false),
            ) {
                providers.forEach { p ->
                    DropdownMenuItem(
                        modifier = Modifier.focusProperties { canFocus = false },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProviderAvatar(p.provider, p.displayName, size = 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(p.displayName)
                            }
                        },
                        onClick = { onSelectProvider(p.provider); providerMenu = false },
                    )
                }
            }
        }
        // 模型：圆形按钮内显示该模型所属厂商的模型头像
        Box {
            ToolCircleButton(
                onClick = { modelMenu = true },
                contentDescription = "切换模型（当前${modelLabel(llmModel)}）",
                container = Color.Transparent,
                contentColor = Color.Transparent,
            ) {
                ModelAvatar(currentProvider, modelLabel(llmModel), size = 40.dp)
            }
            DropdownMenu(
                expanded = modelMenu,
                onDismissRequest = { modelMenu = false },
                // 不抢主窗口焦点，避免输入框失焦导致输入法收起
                properties = PopupProperties(focusable = false),
            ) {
                availableModels.forEach { m ->
                    DropdownMenuItem(
                        modifier = Modifier.focusProperties { canFocus = false },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ModelAvatar(currentProvider, modelLabel(m), size = 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(m)
                            }
                        },
                        onClick = { onSelectModel(m); modelMenu = false },
                    )
                }
            }
        }
        // 思考强度：圆形闪电图标，随档位变换底色/图标颜色
        val effortContainer = when (reasoningEffort) {
            "off" -> MaterialTheme.colorScheme.surfaceVariant
            "high" -> MaterialTheme.colorScheme.primaryContainer
            "max" -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val effortContent = when (reasoningEffort) {
            "off" -> MaterialTheme.colorScheme.onSurfaceVariant
            "high" -> MaterialTheme.colorScheme.onPrimaryContainer
            "max" -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Box {
            ToolCircleButton(
                onClick = { effortMenu = true },
                contentDescription = "思考强度（当前${effortLabel(reasoningEffort)}）",
                container = effortContainer,
                contentColor = effortContent,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = effortMenu,
                onDismissRequest = { effortMenu = false },
                // 不抢主窗口焦点，避免输入框失焦导致输入法收起
                properties = PopupProperties(focusable = false),
            ) {
                REASONING_EFFORTS.forEach { e ->
                    DropdownMenuItem(
                        modifier = Modifier.focusProperties { canFocus = false },
                        text = { Text(effortLabel(e)) },
                        onClick = { onSelectReasoningEffort(e); effortMenu = false },
                    )
                }
            }
        }
        // 联网搜索：圆形地球图标，开启时高亮显示
        ToolCircleButton(
            onClick = { onToggleWebSearch(!webSearchEnabled) },
            contentDescription = if (webSearchEnabled) "关闭联网搜索" else "开启联网搜索",
            selected = webSearchEnabled,
        ) {
            Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        // 上传文件：圆形回形针图标
        ToolCircleButton(
            onClick = onPickFile,
            contentDescription = "上传文件",
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** 工具栏圆形图标按钮：淡色圆底 + 单个图标/头像，不含文字（菜单里才显示详细文本） */
@Composable
private fun ToolCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    container: Color? = null,
    contentColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val bg = container ?: if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = contentColor ?: if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val label = contentDescription
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            // 工具栏按钮不参与焦点：避免点击时输入框失焦、输入法被系统收起
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = false },
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                content()
            }
        }
    }
}
