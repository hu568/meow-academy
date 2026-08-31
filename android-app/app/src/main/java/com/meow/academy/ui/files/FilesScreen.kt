package com.meow.academy.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.ui.components.AppTopBar
import com.meow.academy.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.io.File

/**
 * 📁 文件管理页（M4 数据中心）。
 *
 * 列表态 + 多选态 + 搜索态；文件操作（新建/重命名/删除/复制/移动/导入）经 [FilesViewModel] 调度；
 * 点击文件通过 [onOpenFile] / [onOpenImage] 回调交给父级（MainScreen）打开统一的
 * [FileEditorScreen] / 图片浮层——状态在父层 [androidx.compose.runtime.saveable.rememberSaveable]
 * 持有，切走 tab / 系统切换明暗色后能原地恢复（喵~）。
 *
 * 薄壳化（2026-08-31，见 plan/plan-filesscreen-refactor.md）：只做五件事——
 * 持有状态（按区域收敛 5 组）、BackHandler 三级响应、Scaffold 骨架（顶栏/底栏/FAB + 面包屑/收藏抽屉）、
 * 三种视图分派、对话框挂载点；内联组件已外迁 [FileScreenComponents.kt]。
 */
@Composable
fun FilesScreen(
    onOpenTerminal: (String) -> Unit = {},
    onOpenFile: (FileEntry) -> Unit = {},
    onOpenImage: (FileEntry) -> Unit = {},
) {
    val vm: FilesViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val repository = remember(context) { FileRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 5 组状态区域（本地暂态，关对话框/菜单后自动重置；仅 drawerExpanded 跨 tab 保留）──

    // ① 搜索态：searchOpen（本地暂态，跨 tab 不保留）
    var searchOpen by remember { mutableStateOf(false) }

    // ② 对话框组：新建/重命名/删除/批量删除/目标选择器
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showBatchDelete by remember { mutableStateOf(false) }
    var targetMode by remember { mutableStateOf<TargetMode?>(null) }
    // 长按单文件复制/移动的目标路径；多选批量操作时保持 null（走 selection），喵~
    var singleOpPath by remember { mutableStateOf<String?>(null) }

    // ③ 菜单组：顶栏更多菜单 / FAB 新建菜单
    var showMoreMenu by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }

    // ④ 长按菜单组：menuEntry（内联宫格菜单，独立出来）
    var menuEntry by remember { mutableStateOf<FileEntry?>(null) }

    // ⑤ 收藏抽屉：drawerExpanded（切 tab / 旋转后原地恢复）
    var drawerExpanded by rememberSaveable { mutableStateOf(false) }

    // SAF 多选导入到当前目录
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }

    // 操作反馈 Snackbar
    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            vm.consumeSnackbar()
        }
    }

    // 系统返回键：搜索 → 多选 → 上级目录
    BackHandler {
        when {
            searchOpen -> { searchOpen = false; vm.clearSearch() }
            state.isMultiSelect -> vm.exitMultiSelect()
            else -> vm.navigateUp()
        }
    }

    val searchActive = searchOpen || state.isSearching

    // 打开单个文件：图片浮层 / 统一编辑器 / 超大转终端 / 二进制提示（列表点击与收藏抽屉共用，喵~）
    val openFileEntry: (FileEntry) -> Unit = { entry ->
        when (openKind(File(entry.path), repository)) {
            FileKind.IMAGE -> onOpenImage(entry)
            FileKind.TEXT, FileKind.MARKDOWN, FileKind.HTML -> onOpenFile(entry)
            FileKind.LARGE_TEXT -> scope.launch { snackbarHostState.showSnackbar("文件过大，请用终端打开") }
            else -> scope.launch { snackbarHostState.showSnackbar("无法预览（二进制或未知格式）") }
        }
    }

    // 条目点击 / 长按：列表 / 宫格 / 瀑布流三种视图共用同一套打开与多选逻辑（喵~）
    val onEntryClick: (FileEntry) -> Unit = { entry ->
        if (state.isMultiSelect) {
            vm.toggleSelect(entry.path)
        } else if (entry.isDirectory) {
            vm.onNavigatedTo(entry.path)
        } else {
            openFileEntry(entry)
        }
    }
    val onEntryLongClick: (FileEntry) -> Unit = { entry ->
        if (!state.isMultiSelect) {
            menuEntry = entry
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "文件管理",
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { onOpenTerminal(state.currentPath) }) {
                            Icon(Icons.Filled.Terminal, contentDescription = "打开终端")
                        }
                        SortMenu(
                            mode = state.sortMode,
                            ascending = state.sortAscending,
                            onSort = { m, a -> vm.setSortMode(m, a) },
                        )
                        // 多选开关（排序右边、更多左边）：再点一次退出多选（喵~）
                        IconButton(
                            onClick = { if (state.isMultiSelect) vm.exitMultiSelect() else vm.enterMultiSelect() },
                        ) {
                            Icon(
                                Icons.Filled.Checklist,
                                contentDescription = "多选",
                                tint = if (state.isMultiSelect) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            NewItemMenu(
                                expanded = showMoreMenu,
                                onDismiss = { showMoreMenu = false },
                                onNewFile = { showNewFile = true },
                                onNewFolder = { showNewFolder = true },
                                onImport = { importLauncher.launch(arrayOf("*/*")) },
                                showHiddenFiles = state.showHiddenFiles,
                                onToggleShowHidden = { vm.toggleShowHidden() },
                                viewMode = state.viewMode,
                                onSelectViewMode = { vm.setViewMode(it) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.isMultiSelect && !searchActive) {
                MultiSelectBar(
                    count = state.selection.size,
                    onCopy = { targetMode = TargetMode.COPY },
                    onMove = { targetMode = TargetMode.MOVE },
                    onDelete = { showBatchDelete = true },
                    onCancel = { vm.exitMultiSelect() },
                )
            }
        },
        floatingActionButton = {
            if (!searchActive) {
                Box {
                    FloatingActionButton(onClick = { fabMenuExpanded = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建")
                    }
                    NewItemMenu(
                        expanded = fabMenuExpanded,
                        onDismiss = { fabMenuExpanded = false },
                        onNewFile = { showNewFile = true },
                        onNewFolder = { showNewFolder = true },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!searchActive) {
                // 绝对路径面包屑：完整层级展示，filesDir/外部根内的段可点，系统前缀段仅展示
                EditableBreadcrumb(
                    path = state.currentPath,
                    onNavigate = { path ->
                        if (path.isBlank()) vm.navigateToInternalRoot()
                        else vm.navigateToPath(path)
                    },
                    isNavigable = remember(repository) { { p -> repository.isWithinRoot(p) } },
                )
                // 收藏抽屉（替代原快捷栏）：收起显示最近 4 个收藏，下拉展开全部（含根目录切换）
                FavoritesDrawer(
                    favorites = state.favorites,
                    expanded = drawerExpanded,
                    externalRootAvailable = state.externalAvailable,
                    currentPath = state.currentPath,
                    onExpandChange = { drawerExpanded = it },
                    onOpenFavorite = { favorite ->
                        if (favorite.isDirectory) {
                            vm.navigateToPath(favorite.path)
                        } else {
                            openFileEntry(favorite)
                        }
                    },
                    onRemoveFavorite = { favorite -> vm.toggleFavorite(favorite.path) },
                    onOpenRoot = { vm.switchRoot(it) },
                )
            }

            if (searchActive) {
                SearchField(
                    query = state.searchQuery,
                    onQueryChange = { vm.onSearchQueryChange(it) },
                    onClose = {
                        searchOpen = false
                        vm.clearSearch()
                    },
                )
                if (state.isSearching) {
                    SearchResultList(
                        results = state.searchResults,
                        onNavigate = { path ->
                            vm.onNavigatedTo(path)
                            searchOpen = false
                            vm.clearSearch()
                        },
                    )
                }
            } else {
                when {
                    state.isLoading && state.entries.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.entries.isEmpty() -> {
                        EmptyState(
                            icon = Icons.Outlined.FolderOpen,
                            title = "空目录",
                            description = "点击右下角 + 新建文件或文件夹，或导入文件",
                        )
                    }
                    else -> {
                        when (state.viewMode) {
                            FileViewMode.LIST -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                                items(state.entries, key = { it.path }) { entry ->
                                    FileListRow(
                                        entry = entry,
                                        multiSelect = state.isMultiSelect,
                                        selected = entry.path in state.selection,
                                        onClick = { onEntryClick(entry) },
                                        onLongClick = { onEntryLongClick(entry) },
                                    )
                                }
                            }
                            FileViewMode.GRID -> FileEntryGrid(
                                entries = state.entries,
                                columns = 3,
                                card = false,
                                multiSelect = state.isMultiSelect,
                                selection = state.selection,
                                onClick = onEntryClick,
                                onLongClick = onEntryLongClick,
                            )
                            FileViewMode.WATERFALL -> FileEntryGrid(
                                entries = state.entries,
                                columns = 2,
                                card = true,
                                multiSelect = state.isMultiSelect,
                                selection = state.selection,
                                onClick = onEntryClick,
                                onLongClick = onEntryLongClick,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 对话框与目标选择器 ──
    if (showNewFile) {
        NewNameDialog(
            title = "新建文件",
            confirmLabel = "创建",
            initialValue = "",
            onConfirm = { vm.createFile(it); showNewFile = false },
            onDismiss = { showNewFile = false },
        )
    }
    if (showNewFolder) {
        NewNameDialog(
            title = "新建文件夹",
            confirmLabel = "创建",
            initialValue = "",
            onConfirm = { vm.createFolder(it); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
    renameTarget?.let { target ->
        NewNameDialog(
            title = "重命名",
            confirmLabel = "确定",
            initialValue = target.name,
            onConfirm = { vm.rename(target.path, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            message = if (target.isDirectory) "将递归删除文件夹「${target.name}」内所有内容，此操作不可撤销。"
            else "确定删除「${target.name}」？此操作不可撤销。",
            onConfirm = { vm.delete(target.path); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    menuEntry?.let { target ->
        FileEntryMenuDialog(
            entry = target,
            favorited = target.path in state.favoritePaths,
            onToggleFavorite = { vm.toggleFavorite(target.path) },
            onCopy = {
                singleOpPath = target.path
                targetMode = TargetMode.COPY
            },
            onMove = {
                singleOpPath = target.path
                targetMode = TargetMode.MOVE
            },
            onEnterMultiSelect = {
                vm.enterMultiSelect()
                vm.toggleSelect(target.path)
            },
            onRename = { renameTarget = target },
            onDelete = { deleteTarget = target },
            onDismiss = { menuEntry = null },
        )
    }
    if (showBatchDelete) {
        ConfirmDeleteDialog(
            title = "删除 ${state.selection.size} 项",
            message = "确定删除选中的 ${state.selection.size} 项？文件夹将连同内容递归删除，此操作不可撤销。",
            onConfirm = { vm.deleteSelection(); showBatchDelete = false },
            onDismiss = { showBatchDelete = false },
        )
    }
    targetMode?.let { mode ->
        // 操作路径：长按单文件传单元素列表，多选批量传当前 selection（喵~）
        val opPaths = remember(singleOpPath, state.selection) {
            singleOpPath?.let(::listOf) ?: state.selection.toList()
        }
        // 锁定的源目录：其自身与子树不可选为目标（防把文件夹移动/复制进自身）
        val lockedDirs = remember(opPaths) { opPaths.filter { File(it).isDirectory } }
        TargetDirPicker(
            repository = repository,
            title = if (mode == TargetMode.COPY) "复制 ${opPaths.size} 项到…" else "移动 ${opPaths.size} 项到…",
            confirmLabel = if (mode == TargetMode.COPY) "复制到此处" else "移动到此处",
            lockedDirs = lockedDirs,
            initialDir = state.currentPath,
            onPick = { targetDir ->
                when (mode) {
                    TargetMode.COPY -> vm.copyPaths(opPaths, targetDir)
                    TargetMode.MOVE -> vm.movePaths(opPaths, targetDir)
                }
                targetMode = null
                singleOpPath = null
            },
            onDismiss = {
                targetMode = null
                singleOpPath = null
            },
        )
    }
}
