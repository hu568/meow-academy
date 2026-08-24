package com.meow.academy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

    // 键盘动画统一以「当前 IME 高度」驱动，而不是等 isImeVisible 翻转后再补动画：
    // 输入栏由 ChatInputArea 的 imePadding 实时顶起，这里只补足导航栏高度——
    // 键盘还高于导航栏时占位为 0（输入栏贴键盘），键盘缩到比导航栏矮时占位补到 76dp，
    // 输入栏总偏移 = max(imeHeight, navHeight)，不会先掉到屏幕底再被导航栏顶回去。
    val density = LocalDensity.current
    val imeHeightPx = WindowInsets.ime.getBottom(density).toFloat()
    val navHeightPx = with(density) { 76.dp.toPx() }
    val bottomPad = with(density) { (navHeightPx - imeHeightPx).coerceAtLeast(0f).toDp() }
    val navVisible = imeHeightPx < navHeightPx
    // 输入法高度归一化进度（0=收起，1=完全唤出），传给聊天页驱动底图放大动画。
    // 这里用「输入框实际抬升量」做分母参考：输入框在键盘高度 ≤ 导航栏时不动，
    // 超过导航栏后才开始被顶起，缩放跟着输入框的抬升走，避免键盘刚冒头就跳到最大。
    val inputLiftPx = (imeHeightPx - navHeightPx).coerceAtLeast(0f)
    val windowHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val imeZoom = (inputLiftPx / (windowHeightPx * 0.3f)).coerceIn(0f, 1f)

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
                    .padding(innerPadding),
            ) {
                when (selectedTab) {
                    // 聊天页底图要铺满全屏（含导航栏下方），所以底部占位传入 ChatScreen，
                    // 由它只垫「内容区」而不压缩底图层；imeZoom 用于输入法同步缩放动画。
                    HomeTab.CHAT -> ChatScreen(bottomPadding = bottomPad, imeZoom = imeZoom)
                    HomeTab.FILES -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomPad),
                    ) {
                        FilesScreen(onOpenTerminal = { dir ->
                            terminalInitialDir = dir
                            terminalOpen = true
                        })
                    }
                    HomeTab.SETTINGS -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomPad),
                    ) {
                        SettingsScreen(repository, onOpenTerminal = {
                            terminalInitialDir = null
                            terminalOpen = true
                        })
                    }
                }
            }
        }

        // 底部导航浮层：可见性直接由 IME 高度驱动——键盘高于导航栏时下滑淡出，
        // 键盘缩到比导航栏矮时上滑淡入；与 bottomPad 同一数据源，输入栏不会被顶得跳变。
        AnimatedVisibility(
            visible = navVisible,
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
