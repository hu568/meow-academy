package com.meow.academy.ui.chat

/**
 * 聊天输入栏 + 工具栏组件。
 * 输入框/发送/停止 + 附加模式胶囊 + 思考强度下拉 + 联网开关 + 上传文件。
 * （provider/model 圆钮已移除：切换全权归右侧看板「模型管理」面板，plan-standard-mode §5.4）
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * 待发送附件（上传后在输入框上方预览）。
 * 输入框内用 `[refId]` 引用，发送时由 ChatScreen 替换成 `[文件名](路径)` Markdown。
 */
data class PendingAttachment(
    val refId: String,
    val displayName: String,
    val path: String,
)

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
    reasoningEffort: String,
    webSearchEnabled: Boolean,
    /** 附加模式当前状态（null = 无附加；胶囊三态由此驱动） */
    attachedMode: AttachedMode?,
    /** 是否有可用会话（一条会话都没有时胶囊禁用置灰） */
    hasSession: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickFile: () -> Unit,
    onAttachPlan: () -> Unit,
    onAttachGoal: (String) -> Unit,
    onDetachAttachedMode: () -> Unit,
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
            // 待发送附件：输入框上方预览（图片显示缩略图 + 可移除），下方分隔线与输入框隔开
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
                            if (isImageFile(att.displayName)) {
                                // 图片附件：显示缩略图
                                val thumbnailShape = RoundedCornerShape(8.dp)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(thumbnailShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    AsyncImage(
                                        model = File(att.path),
                                        contentDescription = att.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            } else {
                                // 非图片附件：加号图标
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "插入引用 ${att.displayName}",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                "(${att.displayName})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = if (isImageFile(att.displayName)) 0.dp else 4.dp),
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
                    // 不设 imeAction（默认 ImeAction.Default），让输入法显示换行键，
                    // 支持多行输入；用户通过右侧发送按钮发送消息喵~
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
                reasoningEffort = reasoningEffort,
                webSearchEnabled = webSearchEnabled,
                attachedMode = attachedMode,
                hasSession = hasSession,
                onSelectReasoningEffort = onSelectReasoningEffort,
                onToggleWebSearch = onToggleWebSearch,
                onPickFile = onPickFile,
                onAttachPlan = onAttachPlan,
                onAttachGoal = onAttachGoal,
                onDetachAttachedMode = onDetachAttachedMode,
            )
        }
    }
}

/** 工具栏：附加模式胶囊 + 思考强度下拉 + 联网开关 + 上传文件 */
@Composable
fun ChatToolbar(
    reasoningEffort: String,
    webSearchEnabled: Boolean,
    attachedMode: AttachedMode?,
    hasSession: Boolean,
    onSelectReasoningEffort: (String) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickFile: () -> Unit,
    onAttachPlan: () -> Unit,
    onAttachGoal: (String) -> Unit,
    onDetachAttachedMode: () -> Unit,
) {
    var effortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 附加模式胶囊（原 provider/model 两圆钮位置；plan/goal 单槽位互斥显示）
        AttachedModeCapsule(
            attachedMode = attachedMode,
            hasSession = hasSession,
            onAttachPlan = onAttachPlan,
            onAttachGoal = onAttachGoal,
            onDetach = onDetachAttachedMode,
        )
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

/** 胶囊三态样式（文案 / 底色 / 内容色 / 是否描边空态） */
private data class CapsuleStyle(
    val label: String,
    val container: Color,
    val contentColor: Color,
    val outlined: Boolean,
)

/** 附加模式胶囊样式：空态描边 → 生效中灰底转圈 → 确认态实色（plan-standard-mode §5.4） */
@Composable
private fun capsuleStyle(mode: AttachedMode?): CapsuleStyle = when {
    mode == null -> CapsuleStyle(
        "附加模式",
        Color.Transparent,
        MaterialTheme.colorScheme.onSurfaceVariant,
        outlined = true,
    )
    mode.pending && mode is AttachedMode.Plan -> CapsuleStyle(
        "规划…",
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
        outlined = false,
    )
    mode.pending -> // Goal 生效中
        CapsuleStyle(
            "目标…",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            outlined = false,
        )
    mode is AttachedMode.Plan -> CapsuleStyle(
        "📋 规划",
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
        outlined = false,
    )
    else -> // Goal 确认态：目标只显示前 8 字摘要
        CapsuleStyle(
            "🎯 目标：" + (mode as? AttachedMode.Goal)?.objective.orEmpty().take(8),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            outlined = false,
        )
}

/**
 * 附加模式胶囊（plan-standard-mode §5.4）：单槽位（plan/goal 互斥显示，以 attachedMode 当前值为准）。
 *
 * 三态：
 * - 空态（attachedMode == null）：淡色描边「附加模式」→ 点击弹选择菜单（📋 规划模式 / 🎯 目标模式）；
 * - 生效中（pending）：「规划…」/「目标…」+ 转圈（命令已发、状态事件未回）；
 * - 确认态：「📋 规划」/「🎯 目标：<前 8 字>」实色 → 点击弹确认框执行 detach。
 *
 * 仅「一条会话都没有」时禁用置灰（contentDescription 提示先新建会话）。
 */
@Composable
private fun AttachedModeCapsule(
    attachedMode: AttachedMode?,
    hasSession: Boolean,
    onAttachPlan: () -> Unit,
    onAttachGoal: (String) -> Unit,
    onDetach: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }
    var detachDialog by remember { mutableStateOf(false) }
    var goalText by remember { mutableStateOf("") }

    val mode = attachedMode
    // 胶囊文案与配色三态
    val confirmed = mode != null && !mode.pending
    val style = capsuleStyle(mode)
    val label = style.label
    val container = style.container
    val contentColor = style.contentColor
    val outlined = style.outlined
    val description = when {
        !hasSession -> "先新建会话"
        mode == null -> "附加模式：规划 / 目标"
        mode is AttachedMode.Plan -> "规划模式（点击关闭）"
        else -> "目标模式（点击关闭）"
    }
    val disabled = !hasSession && mode == null

    Box {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if (outlined) {
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            RoundedCornerShape(20.dp),
                        )
                    } else {
                        Modifier
                    }
                )
                .background(container)
                .alpha(if (disabled) 0.45f else 1f)
                .clickable(enabled = !disabled) {
                    when {
                        mode == null -> menuOpen = true
                        confirmed -> detachDialog = true
                        // pending（生效中）：不响应，等事件确认
                    }
                }
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .semantics { this.contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode != null && mode.pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = contentColor,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 空态选择菜单（风格同思考强度菜单）
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                modifier = Modifier.focusProperties { canFocus = false },
                text = { Text("📋 规划模式") },
                onClick = {
                    menuOpen = false
                    onAttachPlan()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.focusProperties { canFocus = false },
                text = { Text("🎯 目标模式") },
                onClick = {
                    menuOpen = false
                    goalDialog = true
                },
            )
        }
    }

    // 目标输入框（必填）：确认后走 /goal <objective>
    if (goalDialog) {
        AlertDialog(
            onDismissRequest = { goalDialog = false; goalText = "" },
            title = { Text("附加目标模式") },
            text = {
                Column {
                    Text(
                        "告诉喵喵老师要朝哪个目标推进喵~",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        singleLine = true,
                        label = { Text("目标（必填）") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = goalText.isNotBlank(),
                    onClick = {
                        onAttachGoal(goalText)
                        goalText = ""
                        goalDialog = false
                    },
                ) { Text("附加") }
            },
            dismissButton = {
                TextButton(onClick = { goalDialog = false; goalText = "" }) { Text("取消") }
            },
        )
    }

    // 确认态点击 → 关闭确认框（规划 / 目标）
    if (detachDialog && mode != null) {
        val modeName = if (mode is AttachedMode.Plan) "规划" else "目标"
        AlertDialog(
            onDismissRequest = { detachDialog = false },
            title = { Text("是否关闭${modeName}模式？") },
            text = { Text("关闭后模型将退出${modeName}模式，恢复正常对话喵~") },
            confirmButton = {
                TextButton(onClick = {
                    onDetach()
                    detachDialog = false
                }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { detachDialog = false }) { Text("取消") }
            },
        )
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
