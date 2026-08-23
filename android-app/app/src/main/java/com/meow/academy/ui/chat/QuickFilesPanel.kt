package com.meow.academy.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.ui.components.EmptyStateCompact
import com.meow.academy.ui.files.FileListRow
import com.meow.academy.ui.files.FilesViewModel

/**
 * 右侧看板「快捷文件」：浏览工作区文件并把文件附加到聊天输入框。
 * 点目录导航、点文件切换附加；不在面板内打开全屏编辑器（要编辑去文件管理页喵）。
 */
@Composable
fun QuickFilesPanel(
    vm: FilesViewModel,
    panelOpen: Boolean,
    attachedPaths: Set<String>,
    onToggleAttach: (FileEntry) -> Unit,
) {
    val state by vm.uiState.collectAsState()
    LaunchedEffect(panelOpen) { if (panelOpen) vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        // 顶部：返回上级 + 路径简写 + 已附加计数
        val context = LocalContext.current
        val workspace = RuntimeExtractor.workspaceDir(context).absolutePath
        val relative = state.currentPath.removePrefix(workspace).trim('/')
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { vm.navigateUp() },
                enabled = state.currentPath != state.shortcuts.firstOrNull()?.path,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
            }
            Text(
                text = if (relative.isEmpty()) "工作区" else "工作区 / $relative",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "已附加 ${attachedPaths.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        HorizontalDivider()
        when {
            state.isLoading && state.entries.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.entries.isEmpty() -> {
                EmptyStateCompact(
                    icon = Icons.Outlined.FolderOpen,
                    title = "工作区暂无文件",
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileListRow(
                            entry = entry,
                            // 复用多选态渲染“已附加”勾选：selected=附加上，点击=切换附加/进入目录
                            multiSelect = true,
                            selected = entry.path in attachedPaths,
                            onClick = {
                                if (entry.isDirectory) vm.onNavigatedTo(entry.path)
                                else onToggleAttach(entry)
                            },
                            onLongClick = { /* 快捷附件不需要长按 */ },
                        )
                    }
                }
            }
        }
    }
}