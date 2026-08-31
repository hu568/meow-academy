package com.meow.academy.ui.chat

/**
 * 会话抽屉的三个对话框分片：重命名 / 删除 / 批量删除。
 * 状态（renaming/deleting/batchDeleting）由薄壳持有，本组件只负责渲染与回调；
 * 关闭统一走 onDismiss（薄壳清空三个开关，同一时刻至多一个对话框打开，行为等价）。
 */
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.meow.academy.data.chat.SessionEntity

@Composable
internal fun SessionDialogs(
    renaming: SessionEntity?,
    deleting: SessionEntity?,
    batchDeleting: Boolean,
    selectedSessions: List<SessionEntity>,
    onRenameConfirm: (Long, String) -> Unit,
    onDeleteConfirm: (SessionEntity) -> Unit,
    onDeleteManyConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    renaming?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = onDismiss,
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
                TextButton(onClick = { onRenameConfirm(session.id, title); onDismiss() }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除会话") },
            text = { Text("确定删除「" + session.title + "」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { onDeleteConfirm(session); onDismiss() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }

    if (batchDeleting) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("批量删除会话") },
            text = { Text("确定删除已选的 ${selectedSessions.size} 个会话吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { onDeleteManyConfirm(); onDismiss() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}