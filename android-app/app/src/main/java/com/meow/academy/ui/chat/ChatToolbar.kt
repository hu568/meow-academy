package com.meow.academy.ui.chat

/**
 * 聊天输入栏工具栏（ChatInputBar 拆分分片）。
 * 附加模式胶囊 + 思考强度下拉 + 联网开关 + 上传文件 + 通用圆形工具按钮。
 * 纯 UI 组件：交互状态（effortMenu）组件内部持有，回调由上层传入。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp

/** 工具栏：附加模式胶囊 + 思考强度下拉 + 联网开关 + 上传文件 */
@Composable
internal fun ChatToolbar(
    reasoningEffort: String,
    supportedEfforts: List<String>,
    webSearchEnabled: Boolean,
    attachedMode: AttachedMode?,
    hasSession: Boolean,
    onSelectReasoningEffort: (String) -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onPickFile: () -> Unit,
    onAttachPlan: () -> Unit,
    onAttachGoal: (String) -> Unit,
    onDetachAttachedMode: () -> Unit,
) {
    var effortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 附加模式胶囊（原 provider/model 两圆钮位置；plan/goal 单槽位互斥显示）
        AttachedModeCapsule(
            attachedMode = attachedMode,
            hasSession = hasSession,
            onAttachPlan = onAttachPlan,
            onAttachGoal = onAttachGoal,
            onDetach = onDetachAttachedMode,
        )
        // 思考强度：圆形闪电图标，随档位变换底色/图标颜色；模型不支持时禁用置灰
        val effortSupported = supportedEfforts.isNotEmpty()
        val effortContainer = when (reasoningEffort) {
            "off" -> MaterialTheme.colorScheme.surfaceVariant
            "high" -> MaterialTheme.colorScheme.primaryContainer
            "max" -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val effortContent = when (reasoningEffort) {
            "off" -> MaterialTheme.colorScheme.onSurfaceVariant
            "high" -> MaterialTheme.colorScheme.onPrimaryContainer
            "max" -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Box {
            ToolCircleButton(
                onClick = { if (effortSupported) effortMenu = true },
                contentDescription = if (effortSupported) "思考强度（当前${effortLabel(reasoningEffort)}）" else "当前模型不支持思考强度",
                container = effortContainer,
                contentColor = effortContent,
                enabled = effortSupported,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            if (effortSupported) {
                DropdownMenu(
                    expanded = effortMenu,
                    onDismissRequest = { effortMenu = false },
                    // 不抢主窗口焦点，避免输入框失焦导致输入法收起
                    properties = PopupProperties(focusable = false),
                ) {
                    // 档位按当前模型能力动态渲染（DeepSeek=off/low/high/max；第三方按声明）
                    supportedEfforts.forEach { e ->
                        DropdownMenuItem(
                            modifier = Modifier.focusProperties { canFocus = false },
                            text = { Text(effortLabel(e)) },
                            onClick = { onSelectReasoningEffort(e); effortMenu = false },
                        )
                    }
                }
            }
        }
        // 联网搜索：圆形地球图标，开启时高亮显示
        ToolCircleButton(
            onClick = { onToggleWebSearch(!webSearchEnabled) },
            contentDescription = if (webSearchEnabled) "关闭联网搜索" else "开启联网搜索",
            selected = webSearchEnabled,
        ) {
            Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.weight(1f))
        // 上传文件：圆形回形针图标
        ToolCircleButton(
            onClick = onPickFile,
            contentDescription = "上传文件",
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** 工具栏圆形图标按钮：淡色圆底 + 单个图标/头像，不含文字（菜单里才显示详细文本） */
@Composable
private fun ToolCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    container: Color? = null,
    contentColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val bg = container ?: if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = contentColor ?: if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val label = contentDescription
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            // 禁用态整体淡化（背景是自绘的，IconButton 的 enabled 不会自动变灰）
            .alpha(if (enabled) 1f else 0.38f)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            // 工具栏按钮不参与焦点：避免点击时输入框失焦、输入法被系统收起
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = false },
        ) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                content()
            }
        }
    }
}
