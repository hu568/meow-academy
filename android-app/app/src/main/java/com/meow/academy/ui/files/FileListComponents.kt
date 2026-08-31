package com.meow.academy.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.meow.academy.data.files.FileEntry
import java.io.File

/** 列表行：图标 + 名称 + 副标题（大小/时间）+ 多选勾选框；选中项整行亮起（Windows 多选风格，喵~） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    entry: FileEntry,
    multiSelect: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    subtitle: String? = null, // 覆盖默认「大小 · 时间」副标题（快捷面板最近/收藏显示相对路径用，喵~）
) {
    val kind = listKind(entry)
    // 选中亮起：primaryContainer 底色铺在 ListItem 容器后面（容器本身置透明露出高亮）
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(
                if (multiSelect) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            if (kind == FileKind.IMAGE) {
                FileThumbnail(path = entry.path, name = entry.name)
            } else {
                Icon(fileIcon(kind), contentDescription = null, tint = fileColor(kind))
            }
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                subtitle ?: if (entry.isDirectory) formatTime(entry.lastModified)
                else "${formatSize(entry.size)} · ${formatTime(entry.lastModified)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (multiSelect) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        },
    )
}

/**
 * 图片文件缩略图：Coil 采样加载，失败回退图片图标（喵~）。
 * 尺寸与圆角由 [modifier] 决定（默认 40dp 圆角小图给列表行用；网格视图传 fillMaxWidth 覆盖媒体区）。
 */
@Composable
internal fun FileThumbnail(
    path: String,
    name: String,
    modifier: Modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
) {
    SubcomposeAsyncImage(
        model = File(path),
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
        },
        error = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = fileColor(FileKind.IMAGE),
                )
            }
        },
    )
}

/**
 * 宫格 / 瀑布流网格视图：[columns] 一行列数（宫格 3、瀑布流 2），
 * [card] 为 true 时条目带圆角卡片底（瀑布流样式），false 为宫格扁平样式。
 * 点击 / 长按 / 多选行为与列表行一致，由调用方注入（喵~）。
 */
@Composable
fun FileEntryGrid(
    entries: List<FileEntry>,
    columns: Int,
    card: Boolean,
    multiSelect: Boolean,
    selection: Set<String>,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(if (card) 10.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(if (card) 10.dp else 2.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FileGridCell(
                entry = entry,
                card = card,
                multiSelect = multiSelect,
                selected = entry.path in selection,
                onClick = { onClick(entry) },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}

/**
 * 网格单格：媒体区（图片显示缩略图，其余显示类型大图标）+ 名称 + 大小/时间。
 * [card] 瀑布流卡片样式（圆角底色 + 大图标 + 完整副标题）；false 为宫格扁平紧凑样式。
 * 多选时媒体区右上角浮勾选框，与列表行的勾选语义一致（喵~）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: FileEntry,
    card: Boolean,
    multiSelect: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kind = listKind(entry)
    // 选中亮起：Windows 多选风格高亮底色（与列表行同色），卡片态盖过默认 surfaceContainer（喵~）
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        card -> MaterialTheme.colorScheme.surfaceContainer
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (card) 16.dp else 8.dp))
            .background(containerColor)
            .then(
                if (multiSelect) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .padding(
                horizontal = if (card) 12.dp else 6.dp,
                vertical = if (card) 12.dp else 8.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 媒体区：固定高度保证同行格子等高对齐（喵~）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (card) 96.dp else 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == FileKind.IMAGE) {
                FileThumbnail(
                    path = entry.path,
                    name = entry.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (card) 12.dp else 8.dp)),
                )
            } else {
                Icon(
                    fileIcon(kind),
                    contentDescription = null,
                    tint = fileColor(kind),
                    modifier = Modifier.size(if (card) 56.dp else 40.dp),
                )
            }
            if (multiSelect) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                entry.isDirectory -> formatTime(entry.lastModified)
                card -> "${formatSize(entry.size)} · ${formatTime(entry.lastModified)}"
                else -> formatSize(entry.size)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 右上角排序菜单：模式选择 + 升降序切换 */
@Composable
fun SortMenu(
    mode: FileSortMode,
    ascending: Boolean,
    onSort: (FileSortMode, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (mode == FileSortMode.NAME) "名称${if (ascending) " ↑" else " ↓"}" else "名称") },
                onClick = {
                    onSort(FileSortMode.NAME, if (mode == FileSortMode.NAME) !ascending else true)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(if (mode == FileSortMode.SIZE) "大小${if (ascending) " ↑" else " ↓"}" else "大小") },
                onClick = {
                    onSort(FileSortMode.SIZE, if (mode == FileSortMode.SIZE) !ascending else true)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(if (mode == FileSortMode.MODIFIED) "修改时间${if (ascending) " ↑" else " ↓"}" else "修改时间") },
                onClick = {
                    onSort(FileSortMode.MODIFIED, if (mode == FileSortMode.MODIFIED) !ascending else true)
                    expanded = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (ascending && mode != FileSortMode.DEFAULT) "切换为降序" else "切换为升序") },
                onClick = {
                    onSort(
                        if (mode == FileSortMode.DEFAULT) FileSortMode.NAME else mode,
                        !ascending,
                    )
                    expanded = false
                },
            )
        }
    }
}

/** 底部批量操作栏（多选态）：复制 / 移动 / 删除 / 取消 */
@Composable
fun MultiSelectBar(
    count: Int,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("已选 $count 项", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onCopy, enabled = count > 0) { Text("复制") }
        TextButton(onClick = onMove, enabled = count > 0) { Text("移动") }
        TextButton(onClick = onDelete, enabled = count > 0) { Text("删除") }
        TextButton(onClick = onCancel) { Text("取消") }
    }
}