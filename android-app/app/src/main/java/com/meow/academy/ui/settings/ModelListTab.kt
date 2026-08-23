package com.meow.academy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.ModelProfile

/** 模型列表页签：模型卡片 + 添加/获取按钮 */
@Composable
fun ModelsTab(
    provider: String,
    models: List<ModelProfile>,
    onAddModel: () -> Unit,
    onEditModel: (ModelProfile) -> Unit,
    onDeleteModel: (ModelProfile) -> Unit,
    onFetch: () -> Unit,
    onToggleDefault: (ModelProfile) -> Unit,
    onReorder: (List<ModelProfile>) -> Unit,
    currentModel: String,
) {
    Column(Modifier.fillMaxSize()) {
        if (models.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("还没有模型\n点下方「添加新模型」或「获取模型列表」", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(
                "长按模型卡片可上下拖动排序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            ReorderableLazyColumn(
                items = models,
                key = { it.id },
                modifier = Modifier.weight(1f),
                onDragEnd = onReorder,
            ) { m, isDragging ->
                ModelCard(
                    provider = provider,
                    model = m,
                    onEdit = { onEditModel(m) },
                    onDelete = { onDeleteModel(m) },
                    isDefault = currentModel == m.id,
                    onToggleDefault = { onToggleDefault(m) },
                    isDragging = isDragging,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

/**
 * 单个模型卡片：上方头像 + 名称/ID（完整显示），下方右侧紧凑操作按钮。
 * 名称和按钮错行排布，长名称不再被右侧按钮挤压。
 */
@Composable
fun ModelCard(
    provider: String,
    model: ModelProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isDefault: Boolean,
    onToggleDefault: () -> Unit,
    isDragging: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelAvatar(
                    provider = provider,
                    modelName = model.name ?: model.id,
                    size = 36.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = model.name ?: model.id,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (model.supportsImage) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "多模态",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactIconButton(
                    onClick = onToggleDefault,
                    contentDescription = "设为默认",
                    icon = if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                    tint = if (isDefault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactIconButton(
                    onClick = onEdit,
                    contentDescription = "设置",
                    icon = Icons.Filled.Settings,
                )
                CompactIconButton(
                    onClick = onDelete,
                    contentDescription = "删除模型",
                    icon = Icons.Filled.Delete,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 紧凑操作按钮：比默认 IconButton 更小，视觉上不挤占卡片空间 */
@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
    }
}
