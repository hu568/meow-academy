package com.meow.academy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.R
import com.meow.academy.data.files.FileRepository
import com.meow.academy.data.settings.HomeTab
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.ui.chat.ChatScreen
import com.meow.academy.ui.files.FileEditorScreen
import com.meow.academy.ui.files.FilesScreen
import com.meow.academy.ui.files.FilesViewModel
import com.meow.academy.ui.files.ImagePreviewOverlay
import com.meow.academy.ui.settings.SettingsScreen
import com.meow.academy.ui.terminal.TerminalScreen

/** 底部导航板块的展示信息（选中用填充图标，未选中用描边图标） */
private data class TabInfo(
    val tab: HomeTab,
    val labelRes: Int,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

private val TABS = listOf(
    TabInfo(HomeTab.CHAT, R.string.tab_chat, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat),
    TabInfo(HomeTab.FILES, R.string.tab_files, Icons.Filled.Folder, Icons.Outlined.Folder),
    TabInfo(HomeTab.SETTINGS, R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * 主界面骨架：底部导航三板块（💬聊天 / 📁文件管理 / ⚙️我的）+ 终端页（全屏覆盖）。
 *
 * 终端页双入口：设置 → 终端（home 路径）；文件管理 → 终端按钮（知识库目录，M3 前落 home）。
 * 默认首页取自 DataStore；用户手动切换后以手动选择为准（进程重建时
 * 由 rememberSaveable 恢复，若从未手动切换则回到设置里的默认首页）。
 *
 * 文件编辑/图片浮窗状态由顶层 [rememberSaveable] 持有：底部导航切走再切回、
 * 系统切换明暗色触发 Activity 重建时，都能原地恢复在「文件编辑/预览」状态，
 * 不再被踢回文件管理列表（喵~）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeApi::class)
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

    // 文件编辑/预览状态：仅存 path（rememberSaveable 原生支持 String），渲染时按 path
    // 重新构造 FileEntry。FilesScreen 列表层 + FileEditorScreen 互斥：editingPath 非空
    // 时直接渲染编辑器，列表层整体退出 composition（喵~）。
    var editingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    // 顶层 FileRepository：editingPath/previewPath 重建 FileEntry 需 path -> FileEntry，
    // 与 FilesScreen / FileEditorScreen 内部用的是同一份实例（喵~）。
    // LocalContext.current 必须在 Composable 顶层取出来再传给非 Composable 的
    // remember lambda（remember 的 calculation 是 @DisallowComposableCalls，喵~）。
    val appContext = LocalContext.current.applicationContext
    val fileRepository = remember { FileRepository(appContext) }
    // 顶层 FilesViewModel：与 FilesScreen 内部 viewModel() 取的是同一个实例（共享
    // LocalViewModelStoreOwner），保存/删除/重命名后调 refresh() 通知列表更新（喵~）。
    val filesViewModel: FilesViewModel = viewModel()

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
        // 跨 tab 状态持有器：切走文件管理 tab 时 SaveableStateProvider("files") 退出
        // composition，内部所有 rememberSaveable 状态被保存到 holder；切回时恢复。
        // 放在 when(selectedTab) 之外（不随 tab 销毁），且 rememberSaveableStateHolder
        // 自身存于 Bundle——系统切换明暗色触发 Activity 重建时一并恢复（喵~）。
        val saveableStateHolder = rememberSaveableStateHolder()
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
                    // 文件 tab：编辑/预览状态只在「文件管理」tab 内渲染——切到聊天/文档时
                    // SaveableStateProvider 退出 composition（不叠加），切回本 tab 时由
                    // rememberSaveable 恢复编辑态（mode/fieldValue/scroll/undo 全部回原位）。
                    // removeState("files") 在 onCloseEditor 触发，保证打开新文件是全新态（喵~）。
                    HomeTab.FILES -> saveableStateHolder.SaveableStateProvider("files") {
                        FileTabContent(
                            editingPath = editingPath,
                            previewPath = previewPath,
                            bottomPad = bottomPad,
                            fileRepository = fileRepository,
                            filesViewModel = filesViewModel,
                            onOpenFile = { entry -> editingPath = entry.path },
                            onOpenImage = { entry -> previewPath = entry.path },
                            onCloseEditor = {
                                editingPath = null
                                saveableStateHolder.removeState("files")
                            },
                            onOpenTerminal = { dir ->
                                terminalInitialDir = dir
                                terminalOpen = true
                            },
                        )
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
            val haptics = LocalHapticFeedback.current
            val navDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .drawBehind {
                        drawLine(
                            color = navDividerColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                tonalElevation = 0.dp,
            ) {
                TABS.forEach { info ->
                    val selected = info.tab == selectedTab
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.12f else 1f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = 350f),
                        label = "tabIconScale",
                    )
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedTabName = info.tab.name
                            }
                        },
                        icon = {
                            Crossfade(
                                targetState = selected,
                                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                label = "tabIconMorph",
                            ) { isSelected ->
                                Icon(
                                    imageVector = if (isSelected) info.iconSelected else info.iconUnselected,
                                    contentDescription = stringResource(info.labelRes),
                                    modifier = Modifier
                                        .size(26.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        },
                                )
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(info.labelRes),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 「文件管理」tab 内容容器。
 *
 * 三态互斥：列表 / 编辑 / 图片预览，渲染在 tab 内容区（受 selectedTab 约束，
 * 切到聊天/文档时整体退出 composition，不再叠加）。editingPath / previewPath
 * 来自 MainScreen 顶层 rememberSaveable，跨 tab 切走再切回时原地恢复（喵~）。
 *
 * 编辑器/图片预览共享同一根 FileRepository，与 FilesScreen 内部用同一份实例，
 * 保证重命名后的新 path 能被 FileEditorScreen 内部 LaunchedEffect(file.path) 同步（喵~）。
 */
@Composable
private fun FileTabContent(
    editingPath: String?,
    previewPath: String?,
    bottomPad: androidx.compose.ui.unit.Dp,
    fileRepository: com.meow.academy.data.files.FileRepository,
    filesViewModel: FilesViewModel,
    onOpenFile: (com.meow.academy.data.files.FileEntry) -> Unit,
    onOpenImage: (com.meow.academy.data.files.FileEntry) -> Unit,
    onCloseEditor: () -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    // 顶层持有 path（rememberSaveable），渲染时按 path 重新构造 FileEntry——
    // 列表层 FilesViewModel 也是同 ViewModelStoreOwner 的实例，切回列表时 currentPath
    // / 多选 / 搜索态都被原样保留（喵~）。
    val editingFile = remember(editingPath) {
        editingPath?.let { fileRepository.toFileEntry(java.io.File(it)) }
    }
    val previewFile = remember(previewPath) {
        previewPath?.let { fileRepository.toFileEntry(java.io.File(it)) }
    }
    // 文件在编辑/预览期间被外部删掉/重命名 → FileEntry 变 null → 退出浮层回列表
    LaunchedEffect(editingPath, editingFile) {
        if (editingPath != null && editingFile == null) onCloseEditor()
    }
    LaunchedEffect(previewPath, previewFile) {
        if (previewPath != null && previewFile == null) onCloseEditor() // 预览关闭复用同回调
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPad),
    ) {
        when {
            editingFile != null -> {
                // 关键：FileEditorScreen 内部已有 LaunchedEffect(file.path) 同步 currentFile，
                // 重命名时 onRenamed 改 path → editingFile 变新实例 → 内部同步，编辑态保留。
                FileEditorScreen(
                    file = editingFile,
                    repository = fileRepository,
                    onBack = onCloseEditor,
                    onSaved = {
                        filesViewModel.refresh()
                        onCloseEditor()
                    },
                    onRenamed = onOpenFile, // 新 path 交给父级 setEditingPath
                    onDeleted = {
                        filesViewModel.refresh()
                        onCloseEditor()
                    },
                )
            }
            previewFile != null -> {
                ImagePreviewOverlay(
                    model = java.io.File(previewFile.path),
                    displayName = previewFile.name,
                    onDismiss = onCloseEditor,
                )
            }
            else -> FilesScreen(
                onOpenTerminal = onOpenTerminal,
                onOpenFile = onOpenFile,
                onOpenImage = onOpenImage,
            )
        }
    }
}
