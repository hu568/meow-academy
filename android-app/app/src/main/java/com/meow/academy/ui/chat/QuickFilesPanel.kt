package com.meow.academy.ui.chat

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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.ui.components.EmptyStateCompact
import com.meow.academy.ui.files.FileListRow
import com.meow.academy.ui.files.FilesUiState
import com.meow.academy.ui.files.FilesViewModel

/** 快捷面板模式：浏览工作区 / 最近使用 / 收藏（喵~） */
private enum class QuickFilesMode { BROWSE, RECENT, FAVORITES }

/** 模式循环顺序：工作区 → 最近 → 收藏 → 工作区 */
private fun nextMode(mode: QuickFilesMode): QuickFilesMode = when (mode) {
    QuickFilesMode.BROWSE -> QuickFilesMode.RECENT
    QuickFilesMode.RECENT -> QuickFilesMode.FAVORITES
    QuickFilesMode.FAVORITES -> QuickFilesMode.BROWSE
}

/** 切换按钮图标 = 点击后到达的模式（标题文字标当前模式，图标标去向，喵~） */
private fun modeIcon(mode: QuickFilesMode): ImageVector = when (mode) {
    QuickFilesMode.BROWSE -> Icons.Outlined.History
    QuickFilesMode.RECENT -> Icons.Outlined.Star
    QuickFilesMode.FAVORITES -> Icons.Outlined.FolderOpen
}

/** 切换按钮的到达模式名（无障碍描述用） */
private fun modeTargetLabel(mode: QuickFilesMode): String = when (mode) {
    QuickFilesMode.BROWSE -> "最近使用"
    QuickFilesMode.RECENT -> "收藏"
    QuickFilesMode.FAVORITES -> "工作区"
}

/** 快捷条目副标题：workspace 内显示相对路径，外部显示「父目录/文件名」区分同名文件 */
private fun shortcutSubtitle(path: String, workspace: String): String {
    if (path.startsWith(workspace)) {
        val relative = path.removePrefix(workspace).trim('/')
        if (relative.isNotEmpty()) return relative
    }
    val name = path.substringAfterLast('/')
    val parentName = path.substringBeforeLast('/').substringAfterLast('/')
    return if (parentName.isEmpty()) name else "$parentName/$name"
}

/**
 * 右侧看板「快捷文件」：三种模式（标题右侧按钮循环切换，喵~）
 * - 工作区：浏览工作区文件；点目录导航、点文件切换附加；
 * - 最近使用：最近打开/附加过的文件（记录点 = 文件页打开、本面板附加）；
 * - 收藏：文件管理页长按收藏的文件/文件夹。
 * 不在面板内打开全屏编辑器（要编辑去文件管理页喵）。
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

    var mode by rememberSaveable { mutableStateOf(QuickFilesMode.BROWSE) }
    val context = LocalContext.current
    val workspace = RuntimeExtractor.workspaceDir(context).absolutePath

    // 切模式：从最近/收藏回到浏览时刷新一次（面板停在快捷模式期间文件可能被文件页改过）
    val switchMode: (QuickFilesMode) -> Unit = { target ->
        if (target == QuickFilesMode.BROWSE && mode != QuickFilesMode.BROWSE) vm.refresh()
        mode = target
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部：返回上级（或回浏览） + 标题（路径/模式名） + 已附加计数 + 模式切换按钮
        val relative = state.currentPath.removePrefix(workspace).trim('/')
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (mode == QuickFilesMode.BROWSE) vm.navigateUp()
                    else switchMode(QuickFilesMode.BROWSE)
                },
                enabled = mode != QuickFilesMode.BROWSE || state.currentPath != workspace,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = when (mode) {
                    QuickFilesMode.BROWSE -> if (relative.isEmpty()) "工作区" else "工作区 / $relative"
                    QuickFilesMode.RECENT -> "最近使用"
                    QuickFilesMode.FAVORITES -> "收藏"
                },
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
            )
            IconButton(onClick = { switchMode(nextMode(mode)) }) {
                Icon(
                    imageVector = modeIcon(mode),
                    contentDescription = "切换到${modeTargetLabel(mode)}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        when (mode) {
            QuickFilesMode.BROWSE -> BrowseList(state, attachedPaths, vm, onToggleAttach)
            QuickFilesMode.RECENT -> ShortcutList(
                entries = state.recents,
                workspace = workspace,
                attachedPaths = attachedPaths,
                emptyIcon = Icons.Outlined.History,
                emptyTitle = "暂无最近使用的文件",
                onToggleAttach = onToggleAttach,
                onOpenDirectory = { entry ->
                    switchMode(QuickFilesMode.BROWSE)
                    vm.onNavigatedTo(entry.path)
                },
                onLongClick = { entry -> vm.removeRecent(entry.path) },
            )
            QuickFilesMode.FAVORITES -> ShortcutList(
                entries = state.favorites,
                workspace = workspace,
                attachedPaths = attachedPaths,
                emptyIcon = Icons.Outlined.Star,
                emptyTitle = "暂无收藏的文件",
                onToggleAttach = onToggleAttach,
                onOpenDirectory = { entry ->
                    switchMode(QuickFilesMode.BROWSE)
                    vm.onNavigatedTo(entry.path)
                },
                onLongClick = { entry -> vm.toggleFavorite(entry.path) },
            )
        }
    }
}

/** 浏览模式：工作区目录列表（点目录导航、点文件切换附加） */
@Composable
private fun BrowseList(
    state: FilesUiState,
    attachedPaths: Set<String>,
    vm: FilesViewModel,
    onToggleAttach: (FileEntry) -> Unit,
) {
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

/**
 * 最近/收藏模式：快捷条目列表（复用 [FileListRow] 的附加勾选与高亮）。
 * 点文件切换附加、点目录跳目录（切回浏览模式）、长按执行 [onLongClick]（收藏=取消收藏、最近=移出列表）。
 */
@Composable
private fun ShortcutList(
    entries: List<FileEntry>,
    workspace: String,
    attachedPaths: Set<String>,
    emptyIcon: ImageVector,
    emptyTitle: String,
    onToggleAttach: (FileEntry) -> Unit,
    onOpenDirectory: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyStateCompact(icon = emptyIcon, title = emptyTitle)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { entry ->
            FileListRow(
                entry = entry,
                multiSelect = true,
                selected = entry.path in attachedPaths,
                subtitle = shortcutSubtitle(entry.path, workspace),
                onClick = {
                    if (entry.isDirectory) onOpenDirectory(entry) else onToggleAttach(entry)
                },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}
