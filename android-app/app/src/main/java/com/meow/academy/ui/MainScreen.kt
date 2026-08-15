package com.meow.academy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.meow.academy.R
import com.meow.academy.data.settings.HomeTab
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.ui.chat.ChatScreen
import com.meow.academy.ui.files.FilesScreen
import com.meow.academy.ui.settings.SettingsScreen
import com.meow.academy.ui.terminal.TerminalScreen

/** 底部导航板块的展示信息 */
private data class TabInfo(
    val tab: HomeTab,
    val labelRes: Int,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabInfo(HomeTab.CHAT, R.string.tab_chat, Icons.AutoMirrored.Filled.Chat),
    TabInfo(HomeTab.FILES, R.string.tab_files, Icons.Filled.Folder),
    TabInfo(HomeTab.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

/**
 * 主界面骨架：底部导航三板块（💬聊天 / 📁文件管理 / ⚙️我的）+ 终端页（全屏覆盖）。
 *
 * 终端页双入口：设置 → 终端（home 路径）；文件管理 → 终端按钮（知识库目录，M3 前落 home）。
 * 默认首页取自 DataStore；用户手动切换后以手动选择为准（进程重建时
 * 由 rememberSaveable 恢复，若从未手动切换则回到设置里的默认首页）。
 */
@Composable
fun MainScreen(repository: SettingsRepository) {
    val defaultHome by repository.defaultHome.collectAsState(initial = HomeTab.CHAT)

    // null = 尚未手动切换，跟随默认首页
    var selectedTabName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTab = selectedTabName?.let { name ->
        HomeTab.entries.firstOrNull { it.name == name }
    } ?: defaultHome

    // 终端页覆盖（从设置/文件管理进入）
    var terminalOpen by rememberSaveable { mutableStateOf(false) }
    // 真终端：bash 维护真实 cwd，入口语境由 bash 自身决定（文件管理的 cd 联动留待 M3）

    if (terminalOpen) {
        TerminalScreen(onBack = { terminalOpen = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { info ->
                    NavigationBarItem(
                        selected = info.tab == selectedTab,
                        onClick = { selectedTabName = info.tab.name },
                        icon = { Icon(info.icon, contentDescription = null) },
                        label = { Text(stringResource(info.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                HomeTab.CHAT -> ChatScreen()
                HomeTab.FILES -> FilesScreen(onOpenTerminal = {
                    terminalOpen = true
                })
                HomeTab.SETTINGS -> SettingsScreen(repository, onOpenTerminal = {
                    terminalOpen = true
                })
            }
        }
    }
}
