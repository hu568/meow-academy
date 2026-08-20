package com.meow.academy.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.ui.chat.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 编辑页两种视图模式 */
private enum class EditorMode { EDIT, PREVIEW }

/**
 * 文件编辑器（全屏覆盖页）：编辑 / Markdown 预览切换 + 保存。
 *
 * 由 FilesScreen 在点击文本文件时全屏打开；保存成功后回调 [onSaved]，
 * 由调用方负责刷新列表并关闭本页（喵~）。
 *
 * @param file 目标文件条目（name / path / isDirectory / size / lastModified）
 * @param repository 文件数据层（读/写 UTF-8、文本判定、Markdown 判定）
 * @param onBack 返回上一页
 * @param onSaved 保存成功回调（FilesScreen 刷新列表并关闭编辑页）
 */
@Composable
fun FileEditorScreen(
    file: FileEntry,
    repository: FileRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    // 非 null 表示不可预览（二进制 / 超大文件），值为提示文案
    var previewError by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(EditorMode.EDIT) }

    // 系统返回键：v1 从简，不弹「未保存」确认，直接返回
    BackHandler { onBack() }

    // 进入时做大小/二进制兜底检查并读取内容
    LaunchedEffect(file.path) {
        val diskFile = File(file.path)
        previewError = when {
            !repository.isTextFile(diskFile) -> "无法预览（二进制或未知格式）"
            diskFile.length() > FileRepository.TEXT_PREVIEW_LIMIT -> "文件过大，请用终端打开"
            else -> null
        }
        if (previewError == null) {
            content = withContext(Dispatchers.IO) { repository.readText(file.path) }
        }
        isLoading = false
    }

    // 保存：直接 writeText + onSaved；失败 Toast 提示并保留内容
    fun save() {
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { repository.writeText(file.path, content) }
            }.isSuccess
            if (ok) {
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                onSaved()
            } else {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        // 顶栏：返回 / 文件名 / 预览↔编辑切换 / 保存
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row {
                TextButton(onClick = { mode = EditorMode.PREVIEW }) {
                    Text(
                        "预览",
                        color = if (mode == EditorMode.PREVIEW) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { mode = EditorMode.EDIT }) {
                    Text(
                        "编辑",
                        color = if (mode == EditorMode.EDIT) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { save() }, enabled = !isLoading && previewError == null) {
                Icon(Icons.Filled.Save, contentDescription = "保存")
            }
        }

        // 内容区：加载中 / 不可预览 / 编辑 / 预览
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            previewError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = previewError.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            mode == EditorMode.EDIT -> {
                // 多行编辑：等宽字体，填满剩余空间；imePadding 避免键盘遮挡
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                )
            }
            else -> {
                if (repository.isMarkdown(file.name)) {
                    // Markdown：TextView 按内容高度铺开，由外层 Compose verticalScroll 统一滚动，
                    // 避免 TextView 内部 ScrollingMovementMethod 自滚动导致「无惯性、直接刹车」。
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        MarkdownText(content, modifier = Modifier.fillMaxWidth(), streaming = false)
                    }
                } else {
                    // 普通文本：等宽 + 外层 verticalScroll 滚动，顶部对齐
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(content, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
