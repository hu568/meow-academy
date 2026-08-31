package com.meow.academy.ui.files

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.ui.chat.MarkdownText
import java.io.File

/**
 * 编辑器预览区分片（喵~）。
 *
 * 负责：Markdown / HTML(WebView) / 纯文本 三叉预览。
 * 参数显式化（state + 回调），不读薄壳闭包状态。
 */
@Composable
internal fun FileEditorPreviewPane(
    state: EditorUiState,
    currentFile: FileEntry,
    repository: FileRepository,
    previewScroll: ScrollState,
    onHtmlScrollFraction: (Float) -> Unit,
) {
    when {
        repository.isMarkdown(currentFile.name) -> {
            // Markdown：TextView 按内容高度铺开，由外层 Compose verticalScroll 统一滚动，
            // 避免 TextView 内部 ScrollingMovementMethod 自滚动导致「无惯性、直接刹车」。
            // previewScroll 提升到顶层：模式切换时保留滚动位置（喵~）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(previewScroll)
                    .padding(16.dp),
            ) {
                MarkdownText(
                    state.fieldValue.text,
                    modifier = Modifier.fillMaxWidth(),
                    streaming = false,
                )
            }
        }
        repository.isHtmlFile(currentFile.name) -> {
            // HTML：未进入过编辑时直接渲染磁盘文件（不读内存，大文件也能预览）；
            // 已加载/编辑过后渲染内存内容，能看到未保存的修改（喵~）
            HtmlWebView(
                file = File(currentFile.path),
                content = if (state.htmlContentLoaded) state.fieldValue.text else null,
                modifier = Modifier.fillMaxSize(),
                // 初始滚动比例直接用 htmlScrollFraction（WebView 实时上报）——
                // 切 tab 回来时由 rememberSaveable 恢复 → 保留阅读位置；
                // 切模式时 toggleMode 已将 anchorFraction = htmlScrollFraction 后再
                // 切换 mode → WebView 重建，恢复值与 anchorFraction 等价。
                initialScrollFraction = state.htmlScrollFraction,
                onScrollFractionChanged = { onHtmlScrollFraction(it) },
            )
        }
        else -> {
            // 普通文本：等宽 + 外层 verticalScroll 滚动，顶部对齐
            // previewScroll 提升到顶层：模式切换时保留滚动位置（喵~）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(previewScroll)
                    .padding(16.dp),
            ) {
                Text(state.fieldValue.text, fontFamily = FontFamily.Monospace)
            }
        }
    }
}