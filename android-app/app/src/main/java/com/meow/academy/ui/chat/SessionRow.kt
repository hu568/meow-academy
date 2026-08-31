package com.meow.academy.ui.chat

/**
 * 单个会话行（三行布局，plan-standard-mode §5.8）：
 * 标题 titleMedium / 预设·工作区 bodySmall / 紧凑时间 labelSmall。
 * 多选 → 复选框+点击切换；普通 → 点击打开、长按弹菜单。
 * 右滑：偏移累计超阈值回调一次，松手回弹归零；左滑完全不处理也不消费，让事件冒泡给抽屉收回。
 * **手势红线（禁止优化）**：pointerInput key 只用 session.id（放 selectionMode 会让首次右滑
 * 重组销毁 drag handler → 卡片卡住）；不 detectHorizontalDragGestures（会吞左滑）；不 consume。
 */
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionEntity
import kotlin.math.roundToInt

private val SWIPE_TRIGGER_DP = 64.dp
@OptIn(ExperimentalFoundationApi::class) @Composable
internal fun SessionRow(
    session: SessionEntity,
    isCurrent: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    metaLine: String,
    onTap: () -> Unit,
    onSwipeRightTrigger: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val triggerPx = with(density) { SWIPE_TRIGGER_DP.toPx() }
    // 长按菜单展开状态；行的水平拖拽偏移（滑动视觉反馈，松手/触发后归零）
    var longPressMenuExpanded by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    // selectionMode / isCurrent 变化时把任何残留的拖拽偏移弹回 0
    LaunchedEffect(selectionMode, isCurrent) {
        dragOffsetX = 0f
    }
    // 平滑回弹：dragOffsetX 变化 → 200ms 动画到目标值（自然弹回）
    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = tween(durationMillis = 180),
        label = "sessionRowSwipe",
    )
    // 让 pointerInput 永远读到最新的 onSwipeRightTrigger（避免闭包捕获旧值）
    val currentOnSwipeRightTrigger by rememberUpdatedState(onSwipeRightTrigger)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .background(
                    if (isCurrent && !selectionMode) MaterialTheme.colorScheme.surfaceVariant
                    else Color.Transparent,
                )
                // 点击打开会话；长按弹出操作菜单（多选模式下长按不弹）
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = { if (!selectionMode) longPressMenuExpanded = true },
                )
                // 手势红线：key 只用 session.id；只观察不消费；左滑完全让出给抽屉
                .pointerInput(session.id) {
                    var hasTriggered = false
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange().x
                            // 只处理向右拖动；左滑不累计也不消费（抽屉收回手势才能收到）
                            if (delta > 0) {
                                dragOffsetX = (dragOffsetX + delta).coerceAtLeast(0f)
                                if (!hasTriggered && dragOffsetX >= triggerPx) {
                                    hasTriggered = true
                                    currentOnSwipeRightTrigger()
                                }
                            }
                            // 不调用 change.consume()：始终把事件留给外层抽屉手势
                        }
                        // 手指松开：视觉位移回弹归零
                        dragOffsetX = 0f
                        hasTriggered = false
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 三行布局：标题 / 预设名 · 工作区短名 / 紧凑时间（行高约 +16dp，真机目检列表密度）
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSessionTimestamp(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (selectionMode) {
                // 多选模式：右侧显示复选框（用 Checkbox 控件 + 圆形浅色背景增加点击命中）
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onTap() },
                )
            } else {
                // 普通模式：右侧预留与多选 Checkbox 等宽的占位，
                // 避免切换多选时标题/卡片内容发生横向错位
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // 长按弹出的会话操作菜单
        DropdownMenu(
            expanded = longPressMenuExpanded,
            onDismissRequest = { longPressMenuExpanded = false },
            offset = DpOffset(120.dp, 0.dp),
        ) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    longPressMenuExpanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    longPressMenuExpanded = false
                    onDelete()
                },
            )
        }
    }
}