package com.meow.academy.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.meow.academy.rpc.LlmModelInfo

/** 删除提供商确认对话框 */
@Composable
fun DeleteProviderDialog(
    displayName: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除提供商") },
        text = { Text("确定删除「" + displayName + "」吗？会同时删除其 API Key。") },
        confirmButton = {
            TextButton(onClick = { onDelete() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 手动添加模型对话框（输入模型 ID） */
@Composable
fun AddModelDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加新模型") },
        text = {
            Column {
                OutlinedTextField(newId, { newId = it }, label = { Text("模型 ID") }, singleLine = true)
                Text("如 gpt-4o / moonshot-v1-8k", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = newId.trim()
                if (id.isNotEmpty()) {
                    onAdd(id)
                    onDismiss()
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑模型对话框（显示名称 / 上下文窗口 / 最大输出 tokens） */
@Composable
fun EditModelDialog(
    model: ModelProfile,
    onSave: (ModelProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(model) { mutableStateOf(model.name ?: "") }
    var ctx by remember(model) { mutableStateOf(model.contextWindow?.toString() ?: "") }
    var maxTok by remember(model) { mutableStateOf(model.maxTokens?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型设置 · " + model.id) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(ctx, { ctx = it }, label = { Text("上下文窗口") }, singleLine = true)
                OutlinedTextField(maxTok, { maxTok = it }, label = { Text("最大输出 tokens") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = model.copy(
                    name = name.ifBlank { null },
                    contextWindow = ctx.toIntOrNull(),
                    maxTokens = maxTok.toIntOrNull(),
                )
                onSave(updated)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 获取到的远端模型列表对话框（点击条目添加到 models） */
@Composable
fun FetchedModelsDialog(
    models: List<LlmModelInfo>,
    added: Set<String>,
    onAdd: (LlmModelInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("获取到 " + models.size + " 个模型") },
        text = {
            LazyColumn(Modifier.height(300.dp)) {
                items(models) { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onAdd(m) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isAdded = m.id in added
                        Icon(if (isAdded) Icons.Filled.Check else Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.id, style = MaterialTheme.typography.bodyMedium)
                            if (m.name != m.id) Text(m.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
