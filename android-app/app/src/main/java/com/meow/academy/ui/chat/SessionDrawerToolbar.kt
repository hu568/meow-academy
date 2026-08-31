package com.meow.academy.ui.chat

/**
 * 会话抽屉工具栏分片：普通态（标题/过滤/多选/新建）+ 多选态（✕/计数/删除）+ 过滤 DropdownMenu。
 * filterMenuOpen 内部 remember（随抽屉组合树升降、关闭即重置，与现状一致），薄壳不感知；
 * 过滤选择回调 onFilterSelect 由薄壳透传（原 chatVm.setSessionFilter）。
 */
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionDrawerToolbar(
    selectionMode: Boolean,
    selectedCount: Int,
    deleteEnabled: Boolean,
    sessionFilter: String,
    onFilterSelect: (String) -> Unit,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onEnterSelection: () -> Unit,
    onNew: () -> Unit,
) {
    // 过滤菜单展开状态只被工具栏使用，完全下沉到分片内部
    var filterMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            // 多选模式工具栏：✕ 退出 + 标题（已选 N 项）+ 删除
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Filled.Close, contentDescription = "取消多选")
            }
            Text(
                text = "已选 $selectedCount 项",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            IconButton(
                onClick = onDeleteSelected,
                enabled = deleteEnabled,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "批量删除",
                    tint = if (deleteEnabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 普通模式工具栏：标题 + 过滤 + 多选（紧贴新建左边） + 新建
            Text(
                text = "会话",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            // 显示过滤入口：全部会话 / 当前工作区会话（DropdownMenu 单选，持久化到 DataStore）
            Box {
                IconButton(onClick = { filterMenuOpen = true }) {
                    Icon(
                        Icons.Outlined.FilterList,
                        contentDescription = "会话显示过滤",
                        tint = if (sessionFilter == "workspace") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = filterMenuOpen,
                    onDismissRequest = { filterMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("全部会话") },
                        leadingIcon = {
                            if (sessionFilter != "workspace") {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onFilterSelect("all")
                            filterMenuOpen = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("当前工作区会话") },
                        leadingIcon = {
                            if (sessionFilter == "workspace") {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        onClick = {
                            onFilterSelect("workspace")
                            filterMenuOpen = false
                        },
                    )
                }
            }
            IconButton(onClick = onEnterSelection) {
                Icon(
                    Icons.Outlined.ChecklistRtl,
                    contentDescription = "多选",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        }
    }
}