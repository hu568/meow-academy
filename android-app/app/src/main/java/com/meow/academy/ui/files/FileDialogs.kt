package com.meow.academy.ui.files

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.meow.academy.data.files.FileRepository

/** 新建文件 / 新建文件夹 / 重命名 共用名称输入对话框 */
@Composable
fun NewNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && '/' !in name,
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除确认对话框（含递归删除提示 / 批量数量） */
@Composable
fun ConfirmDeleteDialog(
    title: String = "确认删除",
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 复制 / 移动目标目录选择器（文件管理页专用包装，喵~）。
 *
 * 实现已抽取到 [FolderPickerDialog]（plan-standard-mode §二.7 的可复用底子）：
 * 本包装只负责把文件管理页的**原根集语义**（filesDir + App 外部目录双根，即
 * [FileRepository.isWithinRoot] 的根集合）注入进去，签名与行为对调用点（FilesScreen）零变化。
 */
@Composable
fun TargetDirPicker(
    repository: FileRepository,
    title: String,
    confirmLabel: String,
    lockedDirs: List<String>,
    initialDir: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 根集合与 FileRepository.isWithinRoot 一致：filesDir + getExternalFilesDir(null) 双根
    val context = LocalContext.current
    val roots = remember(context) {
        listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { it.absolutePath }
    }
    FolderPickerDialog(
        title = title,
        confirmLabel = confirmLabel,
        initialDir = initialDir,
        roots = roots,
        lockedDirs = lockedDirs,
        onPick = onPick,
        onDismiss = onDismiss,
    )
}