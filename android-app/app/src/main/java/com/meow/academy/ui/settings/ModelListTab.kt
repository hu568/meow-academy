package com.meow.academy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 模型列表页签：模型卡片 + 添加/获取按钮 */
@Composable
fun ModelsTab(
    models: List<ModelProfile>,
    onAddModel: () -> Unit,
    onEditModel: (ModelProfile) -> Unit,
    onFetch: () -> Unit,
    onToggleDefault: (ModelProfile) -> Unit,
    onTest: (ModelProfile) -> Unit,
    currentModel: String,
    testingModel: String?,
) {
    Column(Modifier.fillMaxSize()) {
        if (models.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("还没有模型\n点下方「添加新模型」或「获取模型列表」", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(models, key = { it.id }) { m ->
                    ModelCard(
                        model = m,
                        onEdit = { onEditModel(m) },
                        isDefault = currentModel == m.id,
                        onToggleDefault = { onToggleDefault(m) },
                        onTest = { onTest(m) },
                        testing = testingModel == m.id,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAddModel, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加新模型")
            }
            OutlinedButton(onClick = onFetch, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("获取模型列表")
            }
        }
    }
}

/** 单个模型卡片：头像 + 名称 + 星标默认 + 测试连接 + 设置 */
@Composable
fun ModelCard(
    model: ModelProfile,
    onEdit: () -> Unit,
    isDefault: Boolean,
    onToggleDefault: () -> Unit,
    onTest: () -> Unit,
    testing: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(model.name?.take(1)?.uppercase() ?: model.id.take(1).uppercase(), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name ?: model.id, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleDefault) {
                Icon(
                    if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "设为默认",
                    tint = if (isDefault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTest, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, "测试连接")
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Settings, "设置") }
        }
    }
}
