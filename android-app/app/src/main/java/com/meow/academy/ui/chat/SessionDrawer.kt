package com.meow.academy.ui.chat

/**
 * 会话抽屉组件（聊天页左侧）。
 * 会话列表 + 新建 + 重命名/删除对话框；从 ChatScreen.kt 原子拆出。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.ui.components.EmptyStateCompact

/** 会话抽屉：列表 + 新建 + 重命名/删除对话框 */
@Composable
fun SessionDrawer(
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
            EmptyStateCompact(
                icon = Icons.Outlined.Forum,
                title = "暂无会话",
            )
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
            text = { Text("确定删除「" + session.title + "」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { onDelete(session); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
