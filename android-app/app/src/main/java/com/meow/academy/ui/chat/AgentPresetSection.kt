package com.meow.academy.ui.chat

/**
 * 工作设置面板 ② Agent 预设栏（plan-standard-mode §5.7）：
 * presets/list 动态渲染 + 卡片列表（占位卡机制已随四预设全部播种退役），
 * 卡片说明可折叠，trust=user 支持长按删除（确认框在本文件内）。
 *
 * 纯展示分片：零 vm 直连（纯参数注入），状态与回调全部由薄壳（WorkspaceSettingsPanel，
 * vm 的唯一适配器）注入——锁定判定（locked/blankSessionOpen/currentPresetId）
 * 薄壳算好传入，本分片不再重复计算。
 *
 * 单卡组件拆至 [PresetCard]（对齐 SessionRow.kt 独立成文件的惯例）。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.PresetEntry
import com.meow.academy.ui.components.AppSectionHeader

/**
 * ② Agent 预设：presets/list 动态渲染，卡片说明可折叠，trust=user 长按删除。
 *
 * 局部状态（下沉在本分片，不上浮）：expandedIds（展开的卡片组）+ deletingPreset（待删确认组）。
 */
@Composable
internal fun AgentPresetSection(
    entries: List<PresetEntry>,
    /** 新会话默认预设（DataStore），卡片「默认/设为默认」态判定用 */
    defaultPresetId: String,
    /** 当前会话（或当前空白新会话）实际归属的预设 id（薄壳算好） */
    currentPresetId: String,
    /** 会话已有消息（或在生成）→ 预设已锁定，切换只影响新会话 */
    locked: Boolean,
    /** 当前打开的是空白新会话 → 切换会同步到该会话行 */
    blankSessionOpen: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingPreset by remember { mutableStateOf<PresetEntry?>(null) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        AppSectionHeader("Agent 预设")
        Text(
            text = when {
                locked -> "当前会话已锁定预设，新会话可自由切换喵~"
                blankSessionOpen -> "切换将应用到当前新会话，并设为新会话默认喵~"
                else -> "预设决定新会话的能力组合，仅对新会话生效喵"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
        )
        entries.forEach { entry ->
            val disabled = entry.broken != null
            val isCurrent = entry.id == currentPresetId && !disabled
            val isDefault = entry.id == defaultPresetId && !disabled
            PresetCard(
                entry = entry,
                isCurrent = isCurrent,
                isDefault = isDefault,
                locked = locked,
                blankSessionOpen = blankSessionOpen,
                disabled = disabled,
                expanded = entry.id in expandedIds,
                onToggleExpand = {
                    expandedIds = if (entry.id in expandedIds) expandedIds - entry.id else expandedIds + entry.id
                },
                onSelect = { onSelect(entry.id) },
                onRequestDelete = { deletingPreset = entry },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    deletingPreset?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text("删除预设") },
            text = { Text("确定删除自定义预设「${entry.name ?: entry.id}」吗？此操作不可撤销喵。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry.id)
                    deletingPreset = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingPreset = null }) { Text("取消") } },
        )
    }
}
