package com.meow.academy.ui.chat

/**
 * 附加模式的两个对话框（AttachedModeCapsule 拆分分片）。
 * 目标输入 AlertDialog（必填）+ 确认关闭 AlertDialog（规划 / 目标）。
 * 纯展示组件：状态（goalDialog/detachDialog/goalText）由胶囊层持有，以参数委托（对齐 SessionDialogs 惯例）。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 附加模式的两个对话框：目标输入（必填）→ /goal <objective>；确认态点击 → detach 确认 */
@Composable
internal fun ModeDialogs(
    goalDialog: Boolean,
    detachDialog: Boolean,
    mode: AttachedMode?,
    goalText: String,
    onGoalTextChange: (String) -> Unit,
    onGoalConfirm: () -> Unit,
    onGoalDismiss: () -> Unit,
    onDetachConfirm: () -> Unit,
    onDetachDismiss: () -> Unit,
) {
    // 目标输入框（必填）：确认后走 /goal <objective>
    if (goalDialog) {
        AlertDialog(
            onDismissRequest = onGoalDismiss,
            title = { Text("附加目标模式") },
            text = {
                Column {
                    Text(
                        "告诉喵喵老师要朝哪个目标推进喵~",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = onGoalTextChange,
                        singleLine = true,
                        label = { Text("目标（必填）") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = goalText.isNotBlank(),
                    onClick = onGoalConfirm,
                ) { Text("附加") }
            },
            dismissButton = {
                TextButton(onClick = onGoalDismiss) { Text("取消") }
            },
        )
    }

    // 确认态点击 → 关闭确认框（规划 / 目标）
    if (detachDialog && mode != null) {
        val modeName = if (mode is AttachedMode.Plan) "规划" else "目标"
        AlertDialog(
            onDismissRequest = onDetachDismiss,
            title = { Text("是否关闭${modeName}模式？") },
            text = { Text("关闭后模型将退出${modeName}模式，恢复正常对话喵~") },
            confirmButton = {
                TextButton(onClick = onDetachConfirm) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = onDetachDismiss) { Text("取消") }
            },
        )
    }
}
