package com.meow.academy.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    file: FileEntry,
    repository: FileRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 用 TextFieldValue 而非 String：编辑区需要 selection（光标位置）做自动滚动（喵~）
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
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
            fieldValue = TextFieldValue(withContext(Dispatchers.IO) { repository.readText(file.path) })
        }
        isLoading = false
    }

    // 保存：直接 writeText + onSaved；失败 Toast 提示并保留内容
    fun save() {
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { repository.writeText(file.path, fieldValue.text) }
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
                // 多行编辑器（修复「键盘弹出后跳顶、光标不跟随」BUG 喵~）：
                // 旧实现 OutlinedTextField + fillMaxSize 固定高度靠内部自滚动，键盘弹出时
                // imePadding 使其高度骤减，内部滚动位置被重置到顶部，且光标不会滚回可视区。
                // 新实现：BasicTextField（能拿到 onTextLayout 光标排版信息）+ 官方
                // OutlinedTextFieldDefaults.DecorationBox/ContainerBox 复刻原版描边框外观；
                // 高度随内容铺开、自身不滚动，由外层 verticalScroll 统一滚动 —— 键盘弹出只
                // 压缩视口、不重置滚动位置；再监听光标/视口变化，把光标行滚进视口上半部。
                val editScroll = rememberScrollState()
                val interactionSource = remember { MutableInteractionSource() }
                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                var viewportHeightPx by remember { mutableIntStateOf(0) }
                // 滚动容器在窗口中的 y（配合文本区窗口坐标算内容内偏移）
                var containerY by remember { mutableIntStateOf(0) }
                // 文本区在滚动内容中的纵向偏移（用于把 TextLayoutResult 坐标换算成滚动坐标）
                var fieldOffsetY by remember { mutableIntStateOf(0) }

                // 光标或视口（键盘弹出/收起）变化时，把光标行滚进视口上半部
                LaunchedEffect(fieldValue.selection, textLayout, viewportHeightPx) {
                    val layout = textLayout ?: return@LaunchedEffect
                    if (viewportHeightPx <= 0) return@LaunchedEffect
                    val offset = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
                    val line = layout.getLineForOffset(offset)
                    val lineTop = fieldOffsetY + layout.getLineTop(line).toInt()
                    val lineBottom = fieldOffsetY + layout.getLineBottom(line).toInt()
                    val viewStart = editScroll.value
                    val comfortEnd = viewStart + viewportHeightPx / 2
                    // 已完整落在视口上半部则不打扰；否则滚到视口约 1/4 高度处
                    if (lineTop < viewStart || lineBottom > comfortEnd) {
                        editScroll.animateScrollTo((lineTop - viewportHeightPx / 4).coerceAtLeast(0))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        // onSizeChanged 放在 verticalScroll 外层：测得的是应用 imePadding 后的视口高度
                        .onSizeChanged { viewportHeightPx = it.height }
                        .onGloballyPositioned { containerY = it.positionInWindow().y.toInt() }
                        .verticalScroll(editScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        // BasicTextField 不像 OutlinedTextField 会自动套主题文字色，
                        // 必须显式给 onSurface，否则暗色模式下文字是黑的（喵~）
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        onTextLayout = { textLayout = it },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                    ) { innerTextField ->
                        // 官方 DecorationBox + ContainerBox：与 OutlinedTextField 同款描边框
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = fieldValue.text,
                            innerTextField = {
                                Box(
                                    Modifier.onGloballyPositioned { coords ->
                                        // 相对滚动容器的偏移 = 窗口坐标差 + 已滚过的距离
                                        fieldOffsetY = coords.positionInWindow().y.toInt() -
                                            containerY + editScroll.value
                                    },
                                ) { innerTextField() }
                            },
                            enabled = true,
                            isError = false,
                            singleLine = false,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = interactionSource,
                            colors = OutlinedTextFieldDefaults.colors(),
                            contentPadding = OutlinedTextFieldDefaults.contentPadding(),
                            container = {
                                OutlinedTextFieldDefaults.Container(
                                    enabled = true,
                                    isError = false,
                                    interactionSource = interactionSource,
                                )
                            },
                        )
                    }
                }
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
                        MarkdownText(fieldValue.text, modifier = Modifier.fillMaxWidth(), streaming = false)
                    }
                } else {
                    // 普通文本：等宽 + 外层 verticalScroll 滚动，顶部对齐
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(fieldValue.text, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
