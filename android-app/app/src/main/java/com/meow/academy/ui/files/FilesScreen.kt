package com.meow.academy.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meow.academy.ui.components.AppTopBar
import com.meow.academy.ui.components.EmptyState

/**
 * 📁 文件管理页（占位）。
 *
 * M3 实现：数据中心（知识库文件 / 全部文件 / 三种搜索 / Markdown 渲染与编辑）。
 * M2.5：提供「终端」入口（知识库目录语境，M3 前先落 home）。
 */
@Composable
fun FilesScreen(onOpenTerminal: () -> Unit = {}) {
    Scaffold(
        topBar = { AppTopBar(title = "文件管理") },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = "文件管理开发中",
                description = "知识库导入 · Wiki 索引 · Markdown 渲染",
                actions = {
                    OutlinedButton(onClick = onOpenTerminal) {
                        Icon(Icons.Filled.Terminal, contentDescription = null)
                        Text(" 打开终端（知识库目录）", modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
        }
    }
}
