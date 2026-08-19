package com.meow.academy.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.R
import com.meow.academy.data.settings.HomeTab
import com.meow.academy.data.settings.ResidentMode
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.data.settings.ThemeMode
import com.meow.academy.data.settings.displayName
import com.meow.academy.data.settings.themeSeedToHex
import com.meow.academy.ui.components.AppTopBar

/**
 * ⚙️ 我的/设置页（雏形）。
 *
 * M2.1 范围：默认首页、主题（浅色/深色/跟随系统/自定义+种子色）、常驻三档开关（DataStore 持久化）。
 * 终端 / 模型管理 / 停止后台服务为占位入口，M2.5 / M4 / M2.6 实现。
 */
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onOpenTerminal: () -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(repository))

    val themeMode by vm.themeMode.collectAsState()
    val themeSeedColor by vm.themeSeedColor.collectAsState()
    val defaultHome by vm.defaultHome.collectAsState()
    val residentMode by vm.residentMode.collectAsState()
    val residentMinutes by vm.residentMinutes.collectAsState()
    val llmProvider by vm.llmProvider.collectAsState()
    val llmModel by vm.llmModel.collectAsState()

    var showModelManage by remember { mutableStateOf(false) }
    if (showModelManage) {
        ModelManageScreen(onBack = { showModelManage = false })
        return
    }

    var showHomeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showResidentDialog by remember { mutableStateOf(false) }
    var showMinutesDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { AppTopBar(title = "我的") },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
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
                    subtitle = if (themeMode == ThemeMode.CUSTOM) {
                        themeMode.displayName() + " · " + themeSeedToHex(themeSeedColor)
                    } else {
                        themeMode.displayName()
                    },
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
                        subtitle = residentMinutes.toString() + " 分钟",
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
                    subtitle = "$llmProvider / $llmModel",
                    onClick = { showModelManage = true },
                )
            }
            item {
                val app = LocalContext.current.applicationContext as com.meow.academy.MeowAcademyApp
                SettingsRow(
                    icon = Icons.Filled.Stop,
                    title = stringResource(R.string.settings_stop_service),
                    subtitle = "立即停止 DSH 后台进程",
                    onClick = { app.runtimeManager.stop() },
                )
            }
            item { Text(" ", style = MaterialTheme.typography.bodySmall) }
        }
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
        ThemeDialog(
            selectedMode = themeMode,
            seedColor = themeSeedColor,
            onSelectMode = vm::setThemeMode,
            onSelectSeed = vm::setThemeSeedColor,
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
}
