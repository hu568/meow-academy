package com.meow.academy.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/** 快捷命令（真终端直接发送） */
private val QUICK_COMMANDS = listOf("ls", "pwd", "echo 喵~", "uname -a", "node -v")

/**
 * 终端页（真终端 PTY 版）：直连 terminal-host 的 PTY socket，ANSI 转义序列已解析渲染。
 */
@Composable
fun TerminalScreen(
    initialDir: String? = null,
    vm: TerminalViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
) {
    val lines by vm.lines.collectAsState()
    val connected by vm.connected.collectAsState()
    val runtimeState by vm.runtimeState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 系统返回键：回到进入终端前的页面（文件管理/设置），而不是退出 App
    BackHandler(enabled = onBack != null) {
        onBack?.invoke()
    }

    LaunchedEffect(Unit) {
        vm.start(initialDir)
    }

    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .systemBarsPadding(),
    ) {
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
                text = "终端",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFE6EDF3),
            )
            Spacer(Modifier.weight(1f))
            val status: Pair<String, Color> = when {
                connected -> "● PTY" to Color(0xFF3FB950)
                runtimeState is com.meow.academy.runtime.RuntimeState.Running -> "● 运行中" to Color(0xFFD29922)
                else -> "○ 未运行" to Color(0xFFF85149)
            }
            val label = status.first
            val color = status.second
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            IconButton(onClick = { vm.sendInterrupt() }) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "发送 Ctrl-C",
                    tint = Color(0xFFF85149),
                )
            }
            IconButton(onClick = { vm.clearScreen() }) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = "清空屏幕",
                    tint = Color(0xFF8B949E),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            itemsIndexed(lines) { _, segments ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    segments.forEach { seg ->
                        Text(
                            text = seg.text,
                            color = Color(seg.fg.toLong() and 0xFFFFFFFFL),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_COMMANDS.forEach { cmd ->
                androidx.compose.material3.AssistChip(
                    onClick = { vm.sendInput(cmd) },
                    label = { Text(cmd, fontSize = 12.sp, color = Color(0xFFE6EDF3)) },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xFF161B22),
                        labelColor = Color(0xFFE6EDF3),
                    ),
                )
            }
        }

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
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE6EDF3),
                    fontSize = 14.sp,
                ),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (input.isNotBlank()) { vm.sendInput(input); input = "" }
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "执行", tint = Color(0xFF58A6FF))
            }
        }
    }
}
