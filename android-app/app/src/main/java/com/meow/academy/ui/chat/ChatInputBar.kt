package com.meow.academy.ui.chat

/**
 * 聊天输入栏 + 工具栏组件。
 * 输入框/发送/停止 + 附加模式胶囊 + 思考强度下拉 + 联网开关 + 上传文件。
 * （provider/model 圆钮已移除：切换全权归右侧看板「模型管理」面板，plan-standard-mode §5.4）
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 待发送附件（上传后在输入框上方预览）。
 * 输入框内用 `[refId]` 引用，发送时由 ChatScreen 替换成 `[文件名](路径)` Markdown。
 */
data class PendingAttachment(
    val refId: String,
    val displayName: String,
    val path: String,
)

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
    /** 当前模型支持的思考档位（ChatViewModel 按模型能力动态给出；空 = 不支持，按钮禁用） */
    supportedEfforts: List<String>,
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
            ChatAttachmentPreview(
                attachments = attachments,
                onPickAttachment = onPickAttachment,
                onRemoveAttachment = onRemoveAttachment,
            )
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
                supportedEfforts = supportedEfforts,
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


