package com.meow.academy.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 📁 文件管理页（占位）。
 *
 * M3 实现：数据中心（知识库文件 / 全部文件 / 三种搜索 / Markdown 渲染与编辑）。
 * M2.5：提供「终端」入口（知识库目录语境，M3 前先落 home）。
 */
@Composable
fun FilesScreen(onOpenTerminal: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "📁",
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            text = "文件管理开发中（M3）\n知识库导入 · Wiki 索引 · Markdown 渲染",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = onOpenTerminal) {
                Icon(Icons.Filled.Terminal, contentDescription = null)
                Text(" 打开终端（知识库目录）", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
