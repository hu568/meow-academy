package com.meow.academy.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileSearchResult
import com.meow.academy.ui.components.EmptyState
import java.io.File

/** 复制 / 移动目标模式 */
enum class TargetMode { COPY, MOVE }

/** 搜索输入框（自动聚焦由用户点击输入触发；含清除按钮） */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("文件名包含…") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
            }
        },
    )
}

/**
 * 「新建/导入」菜单：顶栏更多与右下角 FAB 共用同一组动作。
 * [showHiddenFiles] 非空时（仅顶栏更多传入）追加「显示隐藏文件」开关项（. 开头，Linux 习惯）；
 * [viewMode] 非空时（仅顶栏更多传入）再追加「查看方式」分组：列表 / 宫格（一行三项）/ 瀑布流（一行两项），
 * 当前模式用 RadioButton 标出（喵~）。
 */
@Composable
fun NewItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onImport: () -> Unit,
    showHiddenFiles: Boolean? = null,
    onToggleShowHidden: (() -> Unit)? = null,
    viewMode: FileViewMode? = null,
    onSelectViewMode: ((FileViewMode) -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("新建文件") }, onClick = { onDismiss(); onNewFile() })
        DropdownMenuItem(text = { Text("新建文件夹") }, onClick = { onDismiss(); onNewFolder() })
        DropdownMenuItem(text = { Text("导入文件") }, onClick = { onDismiss(); onImport() })
        if (showHiddenFiles != null && onToggleShowHidden != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("显示隐藏文件") },
                trailingIcon = { Checkbox(checked = showHiddenFiles, onCheckedChange = null) },
                onClick = { onDismiss(); onToggleShowHidden() },
            )
        }
        if (viewMode != null && onSelectViewMode != null) {
            HorizontalDivider()
            ViewModeMenuItem("列表视图", FileViewMode.LIST, viewMode, onSelectViewMode, onDismiss)
            ViewModeMenuItem("宫格视图", FileViewMode.GRID, viewMode, onSelectViewMode, onDismiss)
            ViewModeMenuItem("瀑布流视图", FileViewMode.WATERFALL, viewMode, onSelectViewMode, onDismiss)
        }
    }
}

/** 查看方式菜单项：名称 + RadioButton 标示当前模式，点击切换并收起菜单 */
@Composable
fun ViewModeMenuItem(
    label: String,
    mode: FileViewMode,
    current: FileViewMode,
    onSelectViewMode: (FileViewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { RadioButton(selected = current == mode, onClick = null) },
        onClick = { onDismiss(); onSelectViewMode(mode) },
    )
}

/**
 * 长按菜单宫格单格：图标在左、名称在右（整组居中），圆角点击波纹（喵~）。
 * [tint] 同时作用于图标与文字（删除项传 error 色红显）。
 */
@Composable
fun MenuActionCell(
    label: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/** 搜索结果列表：显示相对路径，点击进入所在目录/文件 */
@Composable
fun SearchResultList(
    results: List<FileSearchResult>,
    onNavigate: (String) -> Unit,
) {
    if (results.isEmpty()) {
        EmptyState(icon = Icons.Outlined.Search, title = "无搜索结果", description = "换个关键词试试")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(results, key = { it.path }) { r ->
            val kind = fileKindOf(r.name, r.isDirectory)
            val targetPath = if (r.isDirectory) r.path else File(r.path).parentFile?.absolutePath ?: r.path
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(targetPath) },
                leadingContent = {
                    if (kind == FileKind.IMAGE) {
                        FileThumbnail(path = r.path, name = r.name)
                    } else {
                        Icon(fileIcon(kind), contentDescription = null, tint = fileColor(kind))
                    }
                },
                headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(r.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/**
 * 长按条目菜单（双列宫格 AlertDialog）：收藏/复制/移动/多选/重命名/删除。
 * 各动作回调由调用方（FilesScreen）注入，点击任意动作后统一 [onDismiss] 收起菜单（喵~）。
 */
@Composable
fun FileEntryMenuDialog(
    entry: FileEntry,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            // 双列宫格菜单：图标在左、名称在右，两列等宽；分享/删除红显警示（喵~）
            Column {
                Row(Modifier.fillMaxWidth()) {
                    MenuActionCell(
                        label = if (favorited) "取消收藏" else "收藏",
                        icon = if (favorited) Icons.Filled.StarBorder else Icons.Filled.Star,
                        modifier = Modifier.weight(1f),
                    ) {
                        onDismiss()
                        onToggleFavorite()
                    }
                    MenuActionCell(label = "分享", icon = Icons.Filled.Share, modifier = Modifier.weight(1f)) {
                        onDismiss()
                        onShare()
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    MenuActionCell(label = "复制", icon = Icons.Filled.ContentCopy, modifier = Modifier.weight(1f)) {
                        onDismiss()
                        onCopy()
                    }
                    MenuActionCell(label = "移动", icon = Icons.AutoMirrored.Filled.DriveFileMove, modifier = Modifier.weight(1f)) {
                        onDismiss()
                        onMove()
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    MenuActionCell(label = "多选", icon = Icons.Filled.Checklist, modifier = Modifier.weight(1f)) {
                        onDismiss()
                        onEnterMultiSelect()
                    }
                    MenuActionCell(label = "重命名", icon = Icons.Filled.Edit, modifier = Modifier.weight(1f)) {
                        onDismiss()
                        onRename()
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    MenuActionCell(
                        label = "删除",
                        icon = Icons.Filled.Delete,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    ) {
                        onDismiss()
                        onDelete()
                    }
                    // 右侧留白，保持双列对齐（喵~）
                    Spacer(Modifier.weight(1f))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
