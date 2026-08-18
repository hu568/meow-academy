package com.meow.academy.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 长按拖拽排序的 LazyColumn。
 *
 * 拖拽/换位/动画/边缘自动滚动全部交给成熟库 `sh.calvin.reorderable`
 * （Home Assistant、Pocket Casts 等在用），这里只做一层薄封装：
 * 库在拖拽过程中回调 [rememberReorderableLazyListState] 的 onMove，
 * 我们实时更新本地顺序；松手时把最终顺序一次性回调给上层持久化。
 */
@Composable
fun <T : Any> ReorderableLazyColumn(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDragEnd: (List<T>) -> Unit,
    itemContent: @Composable (item: T, isDragging: Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    // 本地顺序：拖拽中先只改这里；上层 items 变化（异步加载/持久化回写）时重新同步。
    var localItems by remember(items) { mutableStateOf(items) }
    val currentItems by rememberUpdatedState(items)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val current = localItems
        if (from.index in current.indices && to.index in 0..current.size) {
            localItems = current.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        itemsIndexed(localItems, key = { _, item -> key(item) }) { _, item ->
            val itemKey = key(item)
            ReorderableItem(
                state = reorderableState,
                key = itemKey,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .longPressDraggableHandle(
                            enabled = enabled,
                            onDragStopped = {
                                if (localItems != currentItems) {
                                    currentOnDragEnd(localItems)
                                }
                            },
                        ),
                ) {
                    itemContent(item, isDragging)
                }
            }
        }
    }
}
