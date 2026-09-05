package com.meow.academy.ui.chat

/**
 * 单张预设卡片（同原版 DSH「名称 + 描述」展示风格，喵~）：
 * - 卡片头：名称（name 缺省回退 id）+ 右侧当前/默认高亮 ✓（可选卡行尾为「设为默认」动作）；
 * - 说明可折叠：默认一行 ellipsis，点卡片展开完整说明；
 * - broken 预设灰显 + 展开后显示原因，标注「不可用」不可选；
 * - trust == "user" 的自定义预设长按删除（确认框在 AgentPresetSection）。
 *
 * 纯展示组件：五态（当前·默认 / 当前 / 应用到当前 / 默认 / 设为默认 / 不可用）全部由参数决定，
 * 对齐 SessionRow.kt 独立成文件的惯例。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.PresetEntry

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PresetCard(
    entry: PresetEntry,
    /** 当前会话（或当前空白新会话）实际归属的预设 */
    isCurrent: Boolean,
    /** 新会话默认预设（DataStore） */
    isDefault: Boolean,
    /** 会话已有消息 → 预设已锁定，切换只影响新会话 */
    locked: Boolean,
    /** 当前打开的是空白新会话 → 切换会同步到该会话行 */
    blankSessionOpen: Boolean,
    disabled: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val containerColor = when {
        isCurrent || isDefault -> MaterialTheme.colorScheme.primaryContainer
        disabled -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onToggleExpand,
                // 仅 trust=user 的真实自定义预设支持长按删除（内置不可删，喵~）
                onLongClick = if (!disabled && entry.trust == "user") onRequestDelete else null,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 卡片头：名称 + 右侧状态/动作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name ?: entry.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    isCurrent && isDefault -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "当前·默认预设",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "当前·默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    isCurrent -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "当前会话预设",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    isDefault && blankSessionOpen -> {
                        Text(
                            text = "应用到当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onSelect)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    isDefault -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "当前默认预设",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    !disabled -> Text(
                        text = "设为默认",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSelect)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    else -> Text(
                        text = "不可用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            // 说明：默认一行 ellipsis，点卡片展开完整说明
            entry.description?.let { description ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // broken 预设：展开后显示解析失败原因
            entry.broken?.let { reason ->
                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "解析失败：$reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 行尾注明：只有带「设为默认 / 应用到当前」动作的卡才显示（纯当前/纯默认卡无动作，不重复说明，喵~）
            if (!disabled && !isCurrent && (!isDefault || blankSessionOpen)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        locked -> "切换只影响新会话"
                        blankSessionOpen -> "将应用到当前新会话"
                        else -> "对新会话生效"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
