package com.meow.academy.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus

/** 单条消息行：按角色分发到用户气泡 / 助手分段主体 */
@Composable
fun MessageRow(msg: MessageEntity) {
    when (msg.role) {
        MessageRole.USER -> UserBubble(msg.content)
        MessageRole.ASSISTANT -> {
            val segments = parseSegments(msg.segmentsJson)
            if (segments != null) {
                AssistantBody(
                    segments = segments,
                    status = msg.status,
                )
            } else {
                // 旧消息兼容：segmentsJson 为空时回退 thinking + toolCallsJson
                Column {
                    msg.thinking.takeIf { it.isNotBlank() }?.let { ThinkingCard(it) }
                    msg.toolCallsJson?.let { json ->
                        parseToolCalls(json).forEach { ToolCard(it) }
                    }
                    AssistantBubble(msg.content, msg.status)
                }
            }
        }
    }
}

/**
 * 助手消息主体：组外思考/文本按到达顺序展示（思考是独立折叠卡、文本是气泡，均不并入工具组）；
 * 工具调用序列折叠成一组，组内若存在「运行中」的工具则自动展开（运行输出完自动收起）。
 */
@Composable
fun AssistantBody(
    segments: List<Segment>,
    status: MessageStatus,
) {
    val toolIndices = segments.indices.filter { segments[it] is Segment.Tool }

    Column {
        if (toolIndices.isEmpty()) {
            // 无工具：思考折叠卡 + 文本气泡，按顺序
            segments.forEach { seg ->
                when (seg) {
                    is Segment.Reasoning -> ThinkingCard(seg.text)
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> Unit
                }
            }
        } else {
            val firstTool = toolIndices.first()
            val lastTool = toolIndices.last()
            // 工具组前（对提问的思考 / 干活前的回复，均不并入工具组）
            for (i in 0 until firstTool) {
                when (val seg = segments[i]) {
                    is Segment.Reasoning -> ThinkingCard(seg.text)
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> Unit
                }
            }
            // 工具调用序列折叠成一组
            ToolGroup(segments.subList(firstTool, lastTool + 1), status)
            // 工具组后（干活完成后的思考 / 最终回复）
            for (i in lastTool + 1 until segments.size) {
                when (val seg = segments[i]) {
                    is Segment.Reasoning -> ThinkingCard(seg.text)
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> Unit
                }
            }
        }
        // 空态兜底（尚无任何内容：加载中 / 空回复）
        if (segments.isEmpty()) {
            AssistantBubble("", status)
        }
    }
}

/** 文本气泡（Text 段）：流式用纯文本，完成后用 Markdown 渲染 */
@Composable
fun TextBubble(text: String, status: MessageStatus) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            if (status == MessageStatus.STREAMING) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            } else {
                MarkdownText(text)
            }
        }
    }
}

/** 思考段（工具组内）：组展开后直接展示，不单独折叠 */
@Composable
fun ThinkingBlock(thinking: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            "🧠 思考",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = thinking,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 工具调用折叠组：收起时显示「🔧 工具调用 xN」；有运行中的工具时自动展开，运行完自动收起 */
@Composable
fun ToolGroup(segments: List<Segment>, status: MessageStatus) {
    val toolCount = segments.count { it is Segment.Tool }
    val hasRunning = segments.any {
        it is Segment.Tool && it.call.result.isBlank() && !it.call.isError
    }
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = hasRunning || userExpanded

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { userExpanded = !userExpanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🔧 工具调用 x" + toolCount,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "▾ 收起" else "▸ 展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            segments.forEach { seg ->
                when (seg) {
                    is Segment.Reasoning -> ThinkingBlock(seg.text)
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> ToolCard(seg.call)
                }
            }
        }
    }
}

/** 用户消息气泡（右侧，主色容器） */
@Composable
fun UserBubble(text: String) {
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

/** 助手整条消息气泡（兼容旧数据：纯 content 渲染） */
@Composable
fun AssistantBubble(content: String, status: MessageStatus) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            when {
                content.isBlank() && status == MessageStatus.STREAMING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("思考中…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                content.isNotBlank() && status == MessageStatus.STREAMING -> Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                )
                content.isNotBlank() -> MarkdownText(content)
                else -> Text(
                    status.takeIf { it == MessageStatus.ERROR }?.let { "⚠️ 出错" } ?: "（空回复）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** thinking 折叠胶囊（默认收起一行，点击展开） */
@Composable
fun ThinkingCard(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "🧠 思考过程",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        Text(
            text = thinking,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 8.dp),
        )
    }
}

/** 工具调用胶囊：默认折叠成一行（图标 + 工具名 + 状态 + 箭头），点击展开参数/结果 */
@Composable
fun ToolCard(tool: ToolCallInfo) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    val statusMark = when {
        tool.isError -> "✗"
        tool.result.isNotBlank() -> "✓"
        else -> "…"
    }
    val statusColor = when {
        tool.isError -> MaterialTheme.colorScheme.error
        tool.result.isNotBlank() -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (tool.isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "🛠 " + tool.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(statusMark, color = statusColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 8.dp)) {
            if (tool.arguments.isNotBlank()) {
                Text(
                    "参数：" + tool.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (tool.result.isNotBlank()) {
                Text(
                    "结果：" + tool.result.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (tool.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (!tool.isError) {
                Text(
                    "执行中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
