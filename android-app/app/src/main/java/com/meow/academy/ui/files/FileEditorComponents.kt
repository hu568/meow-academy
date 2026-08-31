package com.meow.academy.ui.files

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 编辑器顶栏 + 更多菜单（喵~）。
 *
 * 薄壳只负责装配状态并转发回调；本组件持有 DropdownMenu 的展开瞬态。
 */
@Composable
internal fun FileEditorTopBar(
    onBack: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    canToggleMode: Boolean,
    mode: EditorMode,
    onToggleMode: () -> Unit,
    wrapMode: Boolean,
    onWrapModeChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "恢复")
        }
        IconButton(
            onClick = onSave,
            // HTML 未切过编辑模式时内容尚未读入内存，禁用保存防止覆盖成空文件（喵~）
            enabled = canSave,
        ) {
            Icon(Icons.Filled.Save, contentDescription = "保存")
        }
        IconButton(onClick = onToggleMode, enabled = canToggleMode) {
            if (mode == EditorMode.EDIT) {
                Icon(Icons.Filled.Visibility, contentDescription = "预览")
            } else {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
        }
        Box {
            IconButton(onClick = { showMoreMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                // 换行模式切换：当前模式带对勾，点击切换（喵~）
                DropdownMenuItem(
                    text = { Text("自动换行") },
                    leadingIcon = {
                        if (wrapMode) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        showMoreMenu = false
                        if (!wrapMode) onWrapModeChange(true)
                    },
                )
                DropdownMenuItem(
                    text = { Text("不换行") },
                    leadingIcon = {
                        if (!wrapMode) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        showMoreMenu = false
                        if (wrapMode) onWrapModeChange(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = {
                        showMoreMenu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMoreMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 删除确认对话框（喵~） */
@Composable
internal fun FileDeleteConfirmDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除文件") },
        text = { Text("确定删除「$fileName」？此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 重命名对话框：文件名（不含后缀）与后缀分两个输入框。
 * 后缀输入框自动补点：用户填 `md` 会归一化为 `.md`（喵~）。
 */
@Composable
fun RenameFileDialog(
    fileName: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialBase, initialExt) = remember(fileName) { splitFileName(fileName) }
    var base by remember(fileName) { mutableStateOf(initialBase) }
    var ext by remember(fileName) { mutableStateOf(initialExt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    label = { Text("文件名（不含后缀）") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ext,
                    onValueChange = { ext = it },
                    singleLine = true,
                    label = { Text("后缀（如 .md）") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(base.trim(), ext.trim()) },
                enabled = base.isNotBlank() &&
                    '/' !in base && '\u0000' !in base &&
                    '/' !in ext && '\u0000' !in ext,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把文件名拆成「不带后缀的主名」+「含点的后缀」（.gitignore 这类隐藏文件整体算主名，喵~） */
private fun splitFileName(name: String): Pair<String, String> {
    val idx = name.lastIndexOf('.')
    return if (idx > 0) name.substring(0, idx) to name.substring(idx) else name to ""
}

/**
 * 浮动文件名滚动条（喵~）。
 *
 * 文字比容器宽时向左循环滚动；否则静态居中展示。
 * 点击可触发重命名。独立占据顶栏与编辑区之间的一行空隙，不遮编辑区圆角框。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun MarqueeTitle(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.titleMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val measured = remember(text, textStyle, textMeasurer) {
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = textStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
    var singleTextWidthPx by remember { mutableIntStateOf(measured.size.width) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    // 兜底：静态文字真被省略号截断时，强制切换滚动态（防预测量不准导致不滚动，喵~）
    var overflowDetected by remember { mutableStateOf(false) }
    // 两份文字之间的间隔（固定 dp，不依赖字体空格宽度），滚完一份正好第二份接上（喵~）
    val loopGapPx = with(LocalDensity.current) { 32.dp.toPx() }.roundToInt()
    val scrollDistancePx = singleTextWidthPx + loopGapPx
    val shouldScroll = overflowDetected ||
        (containerWidthPx > 0 && singleTextWidthPx > containerWidthPx)

    // 只在需要滚动时启动无限动画：静态展示不空转，且滚动总是从起点开始（喵~）
    val progress = if (shouldScroll) {
        val transition = rememberInfiniteTransition(label = "marqueeTitle")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (scrollDistancePx > 0) {
                        (scrollDistancePx * 10).coerceIn(2500, 15000)
                    } else {
                        3000
                    },
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "marqueeScroll",
        ).value
    } else {
        0f
    }

    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .onSizeChanged { containerWidthPx = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (shouldScroll) {
            Row(
                modifier = Modifier
                    // 关键：解除父容器宽度约束，让双份文字按完整宽度排版，
                    // 否则超出部分不参与布局，向左滚动只会滚出空白（喵~）
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    // 用实际排版宽度校正滚动距离（预测量可能受字体/密度影响，这里最准）
                    .onGloballyPositioned { coords ->
                        val totalWidth = coords.size.width
                        if (totalWidth > 0) {
                            singleTextWidthPx = ((totalWidth - loopGapPx) / 2).coerceAtLeast(0)
                        }
                    }
                    .clickable(onClick = onClick)
                    .offset {
                        IntOffset(x = -(progress * scrollDistancePx).roundToInt(), y = 0)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    style = textStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.width(32.dp))
                Text(
                    text = text,
                    style = textStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        } else {
            Text(
                text = text,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    // 静态文字如果出现省略号/视觉溢出，立刻切滚动态（喵~）
                    if (layout.hasVisualOverflow) overflowDetected = true
                },
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}