package com.meow.academy.ui.chat

/**
 * 附加模式胶囊（ChatInputBar 拆分分片）。
 * 胶囊三态（空态描边 / 生效中转圈 / 确认态实色）+ 样式 + 空态选择菜单。
 * 状态薄层：goalDialog/detachDialog/goalText 在此持有，以参数委托 ModeDialogs（对齐 SessionDialogs 惯例）。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp

/** 胶囊三态样式（文案 / 底色 / 内容色 / 是否描边空态） */
internal data class CapsuleStyle(
    val label: String,
    val container: Color,
    val contentColor: Color,
    val outlined: Boolean,
)

/** 胶囊外形（clip 与描边共用，保证同心） */
internal val CapsuleShape = RoundedCornerShape(20.dp)

/** 附加模式胶囊样式：空态描边 → 生效中灰底转圈 → 确认态实色（plan-standard-mode §5.4） */
@Composable
internal fun capsuleStyle(mode: AttachedMode?): CapsuleStyle = when {
    mode == null -> CapsuleStyle("附加模式", Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant, outlined = true)
    mode.pending && mode is AttachedMode.Plan -> CapsuleStyle("规划…", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, outlined = false)
    mode.pending -> // Goal 生效中
        CapsuleStyle("目标…", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, outlined = false)
    mode is AttachedMode.Plan -> CapsuleStyle("📋 规划", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, outlined = false)
    else -> // Goal 确认态：目标只显示前 8 字摘要
        CapsuleStyle("🎯 目标：" + (mode as? AttachedMode.Goal)?.objective.orEmpty().take(8), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, outlined = false)
}

/**
 * 附加模式胶囊（plan-standard-mode §5.4）：单槽位（plan/goal 互斥显示，以 attachedMode 当前值为准）。
 *
 * 三态：
 * - 空态（attachedMode == null）：淡色描边「附加模式」→ 点击弹选择菜单（📋 规划模式 / 🎯 目标模式）；
 * - 生效中（pending）：「规划…」/「目标…」+ 转圈（命令已发、状态事件未回）；
 * - 确认态：「📋 规划」/「🎯 目标：<前 8 字>」实色 → 点击弹确认框执行 detach。
 *
 * 仅「一条会话都没有」时禁用置灰（contentDescription 提示先新建会话）。
 */
@Composable
internal fun AttachedModeCapsule(
    attachedMode: AttachedMode?,
    hasSession: Boolean,
    onAttachPlan: () -> Unit,
    onAttachGoal: (String) -> Unit,
    onDetach: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }
    var detachDialog by remember { mutableStateOf(false) }
    var goalText by remember { mutableStateOf("") }

    val mode = attachedMode
    // 胶囊文案与配色三态
    val confirmed = mode != null && !mode.pending
    val style = capsuleStyle(mode)
    val label = style.label
    val container = style.container
    val contentColor = style.contentColor
    val outlined = style.outlined
    // 空态描边色（重组期取值，drawBehind 块内逐帧使用）
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val description = when {
        !hasSession -> "先新建会话"
        mode == null -> "附加模式：规划 / 目标"
        mode is AttachedMode.Plan -> "规划模式（点击关闭）"
        else -> "目标模式（点击关闭）"
    }
    val disabled = !hasSession && mode == null

    Box {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .clip(CapsuleShape)
                .then(
                    if (outlined) {
                        // 空态描边用 drawBehind 逐帧重画，不用 Modifier.border：
                        // border 的 CacheDrawModifierNode 带绘制缓存，链上任何失效丢失都会
                        // 让描边「冻结」在旧帧；drawBehind 无缓存，每次绘制 pass 都重新执行。
                        Modifier.drawBehind {
                            // 与胶囊外形同心：描边内缩半宽，圆角随 20dp 胶囊（超半高自动收成 stadium）
                            val strokePx = 1.dp.toPx()
                            drawRoundRect(
                                color = outlineColor,
                                topLeft = Offset(strokePx / 2, strokePx / 2),
                                size = Size(size.width - strokePx, size.height - strokePx),
                                cornerRadius = CornerRadius(20.dp.toPx()),
                                style = Stroke(strokePx),
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .background(container)
                // 禁用置灰用 graphicsLayer 常驻节点改 alpha 属性，不用 Modifier.alpha：
                // alpha==1f 时 Modifier.alpha 直接从链上移除节点，0.45f→1f 的结构性变化
                // 会触发 Compose 1.7.1 节点链更新 bug——本 Row 从此丢失所有绘制失效
                //（真机实测：描边/底色永远停在旧帧，只有文字因图层属性单独更新，
                // 重进页面才恢复；0.2.6「首启胶囊只剩文字无框」即此因）。
                .graphicsLayer { this.alpha = if (disabled) 0.45f else 1f }
                .clickable(enabled = !disabled) {
                    when {
                        mode == null -> menuOpen = true
                        confirmed -> detachDialog = true
                        // pending（生效中）：不响应，等事件确认
                    }
                }
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .semantics { this.contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode != null && mode.pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = contentColor,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 空态选择菜单（风格同思考强度菜单）
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                modifier = Modifier.focusProperties { canFocus = false },
                text = { Text("📋 规划模式") },
                onClick = {
                    menuOpen = false
                    onAttachPlan()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.focusProperties { canFocus = false },
                text = { Text("🎯 目标模式") },
                onClick = {
                    menuOpen = false
                    goalDialog = true
                },
            )
        }
    }

    // 对话框委托给 ModeDialogs（纯展示）
    ModeDialogs(
        goalDialog = goalDialog,
        detachDialog = detachDialog,
        mode = mode,
        goalText = goalText,
        onGoalTextChange = { goalText = it },
        onGoalConfirm = {
            onAttachGoal(goalText)
            goalText = ""
            goalDialog = false
        },
        onGoalDismiss = { goalDialog = false; goalText = "" },
        onDetachConfirm = {
            onDetach()
            detachDialog = false
        },
        onDetachDismiss = { detachDialog = false },
    )
}