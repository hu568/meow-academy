package com.meow.academy.ui.chat

/**
 * 聊天页上方悬浮栏（plan-standard-mode §5.5）：
 * todo 与 subagent 两态显示 + 右端小按钮切换（图标轮换 checklist ↔ group）。
 * - 默认折叠 = 仅一行摘要（☑ n/m · 当前：<in_progress 项> / -agent n 运行中）；
 *   点击摘要行展开半透明面板（浮在消息列表上层，消息从面板下方透过），再点收起；
 * - todo 态展开 = 三态清单；subagent 态展开 = 每个子代理一行（provider/status/stopReason/摘要）；
 * - 两态都无数据 → 整条不渲染（不占位）。
 * 当前显示态/展开态组件内 remember 即可（不持久化）。数据来自 ChatViewModel 的 todoState / subagentRuns。
 */

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 悬浮栏（Todo/Subagent 两态，plan-standard-mode §5.5）；两态都无数据时整条不渲染 */
@Composable
fun ChatStatusBar(
    todos: List<TodoItemView>?,
    subagentRuns: List<SubagentRun>,
    modifier: Modifier = Modifier,
) {
    val hasTodos = !todos.isNullOrEmpty()
    val hasAgents = subagentRuns.isNotEmpty()
    if (!hasTodos && !hasAgents) return // 两态都无数据 → 不渲染

    // 显示态：todo 优先；两态都有数据时按钮才可切换（remember 即可，不持久化）
    var preferAgents by remember { mutableStateOf(false) }
    val showAgents = if (hasTodos && hasAgents) preferAgents else hasAgents
    // 展开态：默认折叠只留摘要行，点击摘要行展开/收起（21+ 项也不会常年盖住半屏）
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
            .animateContentSize(),
    ) {
        // 摘要行（点击展开/收起；两态切换走右端小按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (showAgents) Icons.Outlined.Group else Icons.Outlined.Checklist,
                contentDescription = if (showAgents) "子代理" else "任务清单",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (showAgents) agentSummary(subagentRuns) else todoSummary(todos.orEmpty()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasTodos && hasAgents) {
                // 两态都有数据 → 右端小按钮切换（图标轮换：显示另一态的图标）
                IconButton(
                    onClick = { preferAgents = !showAgents },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (showAgents) Icons.Outlined.Checklist else Icons.Outlined.Group,
                        contentDescription = if (showAgents) "切换到任务清单" else "切换到子代理",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 展开/收起箭头
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 半透明面板：仅展开时渲染（消息列表从下方透过）
        if (expanded) {
            if (showAgents) {
                AgentListPanel(subagentRuns)
            } else {
                TodoListPanel(todos.orEmpty())
            }
        }
    }
}

/** todo 折叠摘要：☑ n/m · 当前：<in_progress 项文本> */
private fun todoSummary(todos: List<TodoItemView>): String {
    val completed = todos.count { it.status == "completed" }
    val current = todos.firstOrNull { it.status == "in_progress" }?.content
    return buildString {
        append("☑ ").append(completed).append('/').append(todos.size)
        if (current != null) {
            append(" · 当前：").append(current)
        }
    }
}

/** subagent 折叠摘要：-agent n 运行中（无终态的都算运行中） */
private fun agentSummary(runs: List<SubagentRun>): String {
    val running = runs.count { it.status == null || it.status == "running" }
    return "-agent $running 运行中（共 ${runs.size}）"
}

/** todo 三态清单：pending 空心 / in_progress 高亮 / completed 删除线 */
@Composable
private fun TodoListPanel(todos: List<TodoItemView>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        todos.forEach { item ->
            val completed = item.status == "completed"
            val inProgress = item.status == "in_progress"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        completed -> Icons.Outlined.CheckCircle
                        inProgress -> Icons.AutoMirrored.Filled.ArrowRightAlt
                        else -> Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = item.status,
                    modifier = Modifier.size(14.dp),
                    tint = when {
                        inProgress -> MaterialTheme.colorScheme.primary
                        completed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (inProgress) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        inProgress -> MaterialTheme.colorScheme.primary
                        completed -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (completed) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { this.alpha = if (completed) 0.75f else 1f },
                )
            }
        }
    }
}

/** subagent 清单：每个子代理一行（provider、status、stopReason、收尾摘要） */
@Composable
private fun AgentListPanel(runs: List<SubagentRun>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        runs.forEach { run ->
            Column {
                Text(
                    text = buildString {
                        append("◆ ")
                        append(run.provider ?: "subagent")
                        run.status?.let { append(" · ").append(it) }
                        run.stopReason?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                run.lastMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
