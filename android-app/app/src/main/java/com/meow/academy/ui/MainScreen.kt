package com.meow.academy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
@OptIn(ExperimentalLayoutApi::class)
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
    // 入口目录：文件管理页传入当前浏览目录（自动 cd），设置页为 null（留在默认 cwd）
    var terminalInitialDir by rememberSaveable { mutableStateOf<String?>(null) }

    if (terminalOpen) {
        TerminalScreen(initialDir = terminalInitialDir, onBack = { terminalOpen = false })
        return
    }

    val imeVisible = WindowInsets.isImeVisible
    val bottomPad = remember { Animatable(76f) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            // 键盘弹出：底部占位缩到 0，输入栏贴住键盘；与导航栏下滑淡出同速
            bottomPad.animateTo(0f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        } else {
            // 键盘收起：底部占位长回 76dp，输入栏回到导航栏上方；与导航栏上滑淡入同速
            bottomPad.animateTo(76f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // 外层不重复吃系统栏 inset：每个页面自己的 Scaffold/TopAppBar 负责状态栏，
            // 否则聊天/设置页会出现「双倍状态栏空隙」（外层垫一层 + 内层 TopAppBar 又垫一层）。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {},
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = bottomPad.value.dp),
            ) {
                when (selectedTab) {
                    HomeTab.CHAT -> ChatScreen()
                    HomeTab.FILES -> FilesScreen(onOpenTerminal = { dir ->
                        terminalInitialDir = dir
                        terminalOpen = true
                    })
                    HomeTab.SETTINGS -> SettingsScreen(repository, onOpenTerminal = {
                        terminalInitialDir = null
                        terminalOpen = true
                    })
                }
            }
        }

        // 底部导航浮层：键盘弹出时下滑淡出，键盘收起时上滑淡入。
        // 内容区底部占位由 bottomPad 同步动画：键盘弹出时缩到 0（输入栏贴键盘），
        // 键盘收起时长回 76dp（输入栏回导航栏上方），中间不会留出导航栏高度的空行。
        AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                initialOffsetY = { it },
            ) + fadeIn(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                targetOffsetY = { it },
            ) + fadeOut(animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)),
        ) {
            NavigationBar(modifier = Modifier.fillMaxWidth().height(76.dp)) {
                TABS.forEach { info ->
                    NavigationBarItem(
                        selected = info.tab == selectedTab,
                        onClick = { selectedTabName = info.tab.name },
                        icon = {
                            Icon(
                                info.icon,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(info.labelRes),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        alwaysShowLabel = true,
                    )
                }
            }
        }
    }
}
