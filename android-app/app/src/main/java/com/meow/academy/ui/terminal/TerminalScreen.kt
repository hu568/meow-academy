package com.meow.academy.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/** 快捷命令 */
private val QUICK_COMMANDS = listOf("ls", "pwd", "echo 喵~", "uname -a", "node -v")

/**
 * 🖥️ 终端页（M2.5）：命令输入 + 输出渲染（等宽字体），走 RPC bash。
 */
@Composable
fun TerminalScreen(
    vm: TerminalViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
) {
    val entries by vm.entries.collectAsState()
    val runtimeState by vm.runtimeState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size, entries.lastOrNull()?.output?.length) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(Int.MAX_VALUE)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            // 全屏覆盖页需手动避开系统状态栏/导航栏（MainActivity 用了 edge-to-edge）
            .systemBarsPadding(),
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color(0xFFE6EDF3),
                    )
                }
            }
            Text(
                text = "🖥️ 终端",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFE6EDF3),
            )
            Spacer(Modifier.weight(1f))
            val (runtimeLabel, runtimeColor) = when (val rs = runtimeState) {
                is com.meow.academy.runtime.RuntimeState.Running -> "● 运行中" to Color(0xFF3FB950)
                is com.meow.academy.runtime.RuntimeState.Error -> "⚠ ${rs.message.take(12)}" to Color(0xFFF85149)
                else -> "○ 未运行" to Color(0xFFF85149)
            }
            Text(
                text = runtimeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = runtimeColor,
            )
            IconButton(onClick = { vm.clear() }) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "清空",
                    tint = Color(0xFF8B949E),
                )
            }
        }

        // 输出区
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                TerminalBlock(entry)
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = "喵～ 这里是终端\n输入命令后回车执行（pi RPC bash）\n\n快捷命令：${QUICK_COMMANDS.joinToString("  ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        // 快捷命令
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_COMMANDS.forEach { cmd ->
                androidx.compose.material3.AssistChip(
                    onClick = { vm.runCommand(cmd) },
                    label = { Text(cmd, fontSize = 12.sp) },
                )
            }
        }

        // 输入行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入命令…", color = Color(0xFF8B949E)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE6EDF3),
                    fontSize = 14.sp,
                ),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    vm.runCommand(input)
                    input = ""
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "执行", tint = Color(0xFF58A6FF))
            }
        }
    }
}

/** 单条命令 + 输出块 */
@Composable
private fun TerminalBlock(entry: TerminalEntry) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "❯ ${entry.command}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF58A6FF),
            fontFamily = FontFamily.Monospace,
        )
        if (entry.error != null) {
            Text(
                text = "⚠ ${entry.error}",
                color = Color(0xFFF85149),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        } else {
            Text(
                text = entry.output.ifEmpty { if (entry.isRunning) "…" else "（无输出）" },
                color = Color(0xFFE6EDF3),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (!entry.isRunning) {
                val suffix = when {
                    entry.cancelled -> "（已取消）"
                    entry.exitCode == 0 -> "（exit 0）"
                    else -> "（exit ${entry.exitCode}）"
                }
                Text(
                    text = suffix,
                    color = if (entry.exitCode == 0) Color(0xFF3FB950) else Color(0xFFF85149),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
