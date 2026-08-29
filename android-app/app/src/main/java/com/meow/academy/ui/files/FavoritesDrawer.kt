package com.meow.academy.ui.files

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRoot
import com.meow.academy.data.files.displayName
import com.meow.academy.ui.theme.LocalThemeExtras

/** 收起态最多露出的收藏条数（其余收进展开态，喵~） */
private const val COLLAPSED_VISIBLE_COUNT = 4

/**
 * 收藏抽屉（替代原快捷栏）：放收藏的文件/文件夹快捷方式（喵~）。
 *
 * - 收起态：只显示最近收藏的前 [COLLAPSED_VISIBLE_COUNT] 个 chip（横向可滚动）；
 * - 展开态：向下拉出显示全部收藏（FlowRow 限高滚动）+ 根目录切换项（保留原快捷栏切根能力）；
 * - 抽屉上向下拖动展开、向上拖动收起，右侧箭头点击同样切换；
 * - 点击 chip：文件夹跳目录、文件走打开回调；长按 chip 取消收藏。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FavoritesDrawer(
    favorites: List<FileEntry>,
    expanded: Boolean,
    externalRootAvailable: Boolean,
    currentPath: String,
    onExpandChange: (Boolean) -> Unit,
    onOpenFavorite: (FileEntry) -> Unit,
    onRemoveFavorite: (FileEntry) -> Unit,
    onOpenRoot: (FileRoot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .pointerInput(Unit) {
                // 下拉抽屉手势：向下拖展开、向上拖收起；一次手势只触发一次方向切换（喵~）
                var consumed = false
                detectVerticalDragGestures(
                    onDragStart = { consumed = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (!consumed) {
                            when {
                                dragAmount > 0f -> { consumed = true; onExpandChange(true) }
                                dragAmount < 0f -> { consumed = true; onExpandChange(false) }
                            }
                        }
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    favorites.isEmpty() -> Text(
                        text = "长按文件或文件夹可收藏，快捷方式会显示在这里",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExpandChange(!expanded) }
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                    )
                    !expanded -> Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        favorites.take(COLLAPSED_VISIBLE_COUNT).forEach { favorite ->
                            FavoriteChip(
                                label = favorite.name,
                                icon = favoriteIcon(favorite),
                                iconTint = favoriteIconTint(favorite),
                                selected = favorite.path == currentPath,
                                onClick = {
                                    onExpandChange(false)
                                    onOpenFavorite(favorite)
                                },
                                onLongClick = { onRemoveFavorite(favorite) },
                            )
                        }
                    }
                    else -> FlowRow(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        favorites.forEach { favorite ->
                            FavoriteChip(
                                label = favorite.name,
                                icon = favoriteIcon(favorite),
                                iconTint = favoriteIconTint(favorite),
                                selected = favorite.path == currentPath,
                                onClick = {
                                    onExpandChange(false)
                                    onOpenFavorite(favorite)
                                },
                                onLongClick = { onRemoveFavorite(favorite) },
                            )
                        }
                        // 根目录切换项：收藏之外附赠，替代原快捷栏的切根入口
                        FavoriteChip(
                            label = FileRoot.INTERNAL.displayName(),
                            icon = Icons.Filled.Home,
                            iconTint = MaterialTheme.colorScheme.primary,
                            selected = false,
                            onClick = {
                                onExpandChange(false)
                                onOpenRoot(FileRoot.INTERNAL)
                            },
                        )
                        if (externalRootAvailable) {
                            FavoriteChip(
                                label = FileRoot.EXTERNAL.displayName(),
                                icon = Icons.Filled.SdStorage,
                                iconTint = MaterialTheme.colorScheme.primary,
                                selected = false,
                                onClick = {
                                    onExpandChange(false)
                                    onOpenRoot(FileRoot.EXTERNAL)
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { onExpandChange(!expanded) }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起收藏抽屉" else "展开收藏抽屉",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 收藏项图标：文件夹 / 按文件类型分类（与列表图标同一套） */
private fun favoriteIcon(favorite: FileEntry): ImageVector =
    if (favorite.isDirectory) Icons.Filled.Folder else fileIcon(fileKindOf(favorite.name, false))

/** 收藏项图标色（随深浅主题切换） */
@Composable
private fun favoriteIconTint(favorite: FileEntry): Color =
    fileColor(fileKindOf(favorite.name, favorite.isDirectory))

/**
 * 收藏 chip：图标 + 名称，自绘样式（FilterChip 不支持长按，这里用 combinedClickable 实现长按取消收藏）。
 * 选中态沿用主题扩展色槽 quickBarSelectedContainer / quickBarColor（与原快捷栏视觉一致，喵~）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val extras = LocalThemeExtras.current
    val container = if (selected) {
        extras.quickBarSelectedContainer ?: MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        extras.quickBarColor ?: MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}
