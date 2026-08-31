package com.meow.academy.ui.chat

/**
 * 会话列表分片：空态 / 工作区空态引导 / LazyColumn + 行元信息（预设名·工作区）计算。
 * 只渲染「已过滤」数据；「暂无会话」与「当前工作区还没有会话」由 hasAnySession 区分。
 * 元信息行计算（presetLabel / metaLine）内聚在此，参数显式化（defaultWorkspacePath / presetCatalog / filesDirPath）。
 */
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.model.PresetEntry
import com.meow.academy.ui.components.EmptyStateCompact

@Composable
internal fun ColumnScope.SessionDrawerList(
    sessions: List<SessionEntity>,          // 已过滤（薄壳传 filteredSessions）
    hasAnySession: Boolean,                  // 原始列表是否非空（区分「暂无会话」/「工作区空态」）
    currentId: Long?,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    sessionFilter: String,                   // 元信息行：仅「全部会话」且非默认工作区时显示工作区段
    defaultWorkspacePath: String,
    presetCatalog: List<PresetEntry>,
    filesDirPath: String,
    onTap: (Long) -> Unit,
    onSwipeRightTrigger: (Long) -> Unit,
    onEdit: (SessionEntity) -> Unit,
    onDelete: (SessionEntity) -> Unit,
) {
    when {
        // 原始会话列表为空 →「暂无会话」
        !hasAnySession -> {
            EmptyStateCompact(
                icon = Icons.Outlined.Forum,
                title = "暂无会话",
            )
        }
        // 已过滤列表为空（但原始列表非空）→ 工作区空态引导
        sessions.isEmpty() -> {
            Column(Modifier.fillMaxWidth()) {
                EmptyStateCompact(
                    icon = Icons.Outlined.Forum,
                    title = "当前工作区还没有会话",
                )
                Text(
                    text = "到右侧看板 → 工作设置 可添加或切换工作区喵~",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    val isSelected = session.id in selectedIds
                    // 元信息行：预设显示名（常显）+「 · 」+ 工作区短名
                    // （仅「全部会话」模式且会话不属于当前默认工作区时显示，喵~）
                    val presetLabel = when (val pid = session.presetId) {
                        null -> "默认"
                        else -> presetCatalog.firstOrNull { it.id == pid }?.let { it.name ?: it.id } ?: pid
                    }
                    val showWorkspace = sessionFilter == "all" && session.workspacePath != defaultWorkspacePath
                    val metaLine = if (showWorkspace) {
                        "$presetLabel · " + workspaceShortName(session.workspacePath, filesDirPath)
                    } else {
                        presetLabel
                    }
                    SessionRow(
                        session = session,
                        isCurrent = session.id == currentId,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        metaLine = metaLine,
                        onTap = { onTap(session.id) },
                        onSwipeRightTrigger = { onSwipeRightTrigger(session.id) },
                        onEdit = { onEdit(session) },
                        onDelete = { onDelete(session) },
                    )
                }
            }
        }
    }
}