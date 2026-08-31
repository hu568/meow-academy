package com.meow.academy.ui.chat

/**
 * 待发送附件预览列表（ChatInputBar 拆分分片）。
 * 图片附件显示缩略图 + 可移除；非图片附件显示加号 + 文件名。
 * 纯 UI 组件：状态与回调均由上层传入（对齐「薄壳 + 职责分片」惯例）。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/** 附件预览列表：输入框上方预览（图片显示缩略图 + 可移除），下方分隔线与输入框隔开 */
@Composable
internal fun ChatAttachmentPreview(
    attachments: List<PendingAttachment>,
    onPickAttachment: (PendingAttachment) -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
) {
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
}
