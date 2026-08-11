package com.meow.academy.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.R
import com.meow.academy.data.settings.HomeTab
import com.meow.academy.data.settings.ResidentMode
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.data.settings.ThemeMode

/**
 * ⚙️ 我的/设置页（雏形）。
 *
 * M2.1 范围：默认首页、主题（浅色/深色/跟随系统/自定义）、常驻三档开关（DataStore 持久化）。
 * 终端 / 模型管理 / 停止后台服务为占位入口，M2.5 / M4 / M2.6 实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onOpenTerminal: () -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(repository))

    val themeMode by vm.themeMode.collectAsState()
    val defaultHome by vm.defaultHome.collectAsState()
    val residentMode by vm.residentMode.collectAsState()
    val residentMinutes by vm.residentMinutes.collectAsState()
    val llmProvider by vm.llmProvider.collectAsState()
    val llmModel by vm.llmModel.collectAsState()
    val llmApiKey by vm.llmApiKey.collectAsState()

    var showHomeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showResidentDialog by remember { mutableStateOf(false) }
    var showMinutesDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        // 页头
        item {
            Text(
                text = "⚙️ 我的",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }

        // ── 常规 ──
        item { SectionHeader("常规") }
        item {
            SettingsRow(
                icon = Icons.Filled.Home,
                title = stringResource(R.string.settings_default_home),
                subtitle = defaultHome.displayName(),
                onClick = { showHomeDialog = true },
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_theme),
                subtitle = themeMode.displayName(),
                onClick = { showThemeDialog = true },
            )
        }

        // ── 运行时 ──
        item { SectionHeader("运行时") }
        item {
            SettingsRow(
                icon = Icons.Filled.Power,
                title = stringResource(R.string.settings_resident),
                subtitle = residentMode.displayName(residentMinutes),
                onClick = { showResidentDialog = true },
            )
        }
        if (residentMode == ResidentMode.TIMED) {
            item {
                SettingsRow(
                    icon = Icons.Filled.Terminal,
                    title = "保活时长",
                    subtitle = "${residentMinutes} 分钟",
                    onClick = { showMinutesDialog = true },
                )
            }
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Terminal,
                title = stringResource(R.string.settings_terminal),
                subtitle = "默认 home 路径 · pi RPC bash",
                onClick = onOpenTerminal,
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.ModelTraining,
                title = stringResource(R.string.settings_model),
                subtitle = "$llmProvider / $llmModel" + if (llmApiKey.isBlank()) " · 未配置 Key" else " · Key 已配置",
                onClick = { showModelDialog = true },
            )
        }
        item {
            val app = LocalContext.current.applicationContext as com.meow.academy.MeowAcademyApp
            SettingsRow(
                icon = Icons.Filled.Stop,
                title = stringResource(R.string.settings_stop_service),
                subtitle = "立即停止 Pi 后台进程",
                onClick = { app.runtimeManager.stop() },
            )
        }
        item { Text(" ", style = MaterialTheme.typography.bodySmall) }
    }

    // ── 单选对话框 ──
    if (showHomeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_default_home),
            options = HomeTab.entries.map { it to it.displayName() },
            selected = defaultHome,
            onSelect = { vm.setDefaultHome(it); showHomeDialog = false },
            onDismiss = { showHomeDialog = false },
        )
    }
    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to it.displayName() },
            selected = themeMode,
            onSelect = { vm.setThemeMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }
    if (showResidentDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_resident),
            options = ResidentMode.entries.map { it to it.displayName(0) },
            selected = residentMode,
            onSelect = { vm.setResidentMode(it); showResidentDialog = false },
            onDismiss = { showResidentDialog = false },
        )
    }
    if (showMinutesDialog) {
        SingleChoiceDialog(
            title = "保活时长（分钟）",
            options = listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟"),
            selected = residentMinutes,
            onSelect = { vm.setResidentMinutes(it); showMinutesDialog = false },
            onDismiss = { showMinutesDialog = false },
        )
    }
    if (showModelDialog) {
        ModelConfigDialog(
            provider = llmProvider,
            model = llmModel,
            apiKey = llmApiKey,
            onSave = { p, m, k ->
                vm.setLlmProvider(p)
                vm.setLlmModel(m)
                vm.setLlmApiKey(k)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false },
        )
    }
}

/** 分组小标题 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 设置行（图标 + 标题 + 副标题 + 右箭头） */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            }
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** 通用单选对话框 */
@Composable
private fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = { onSelect(value) },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 模型配置对话框（M2.6 雏形）：provider / model / API Key */
@Composable
private fun ModelConfigDialog(
    provider: String,
    model: String,
    apiKey: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var p by remember { mutableStateOf(provider) }
    var m by remember { mutableStateOf(model) }
    var k by remember { mutableStateOf(apiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Provider") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = m,
                    onValueChange = { m = it },
                    label = { Text("Model") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = k,
                    onValueChange = { k = it },
                    label = { Text("DeepSeek API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "Key 仅存本机（DataStore），注入 pi 进程环境变量，不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(p.trim(), m.trim(), k.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ── 显示名映射 ──

private fun HomeTab.displayName(): String = when (this) {
    HomeTab.CHAT -> "💬 聊天"
    HomeTab.FILES -> "📁 文件管理"
    HomeTab.SETTINGS -> "⚙️ 我的"
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.CUSTOM -> "自定义"
}

private fun ResidentMode.displayName(minutes: Int): String = when (this) {
    ResidentMode.OFF -> "关闭"
    ResidentMode.TIMED -> "有限保活（$minutes 分钟）"
    ResidentMode.ALWAYS -> "一直常驻"
}
