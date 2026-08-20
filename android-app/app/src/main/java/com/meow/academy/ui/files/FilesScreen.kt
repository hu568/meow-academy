package com.meow.academy.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileRepository
import com.meow.academy.data.files.FileSearchResult
import com.meow.academy.data.files.displayName
import com.meow.academy.ui.components.AppTopBar
import com.meow.academy.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.io.File

/**
 * 📁 文件管理页（M4 数据中心）。
 *
 * 列表态 + 多选态 + 搜索态；文件操作（新建/重命名/删除/复制/移动/导入）经 [FilesViewModel] 调度；
 * 点击文本文件打开 [FileEditorScreen]（编辑 + Markdown 预览）；「终端」按钮联动真终端自动 cd 到当前目录。
 */
@Composable
fun FilesScreen(onOpenTerminal: (String) -> Unit = {}) {
    val vm: FilesViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val repository = remember(context) { FileRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 编辑器覆盖页
    var editingFile by remember { mutableStateOf<FileEntry?>(null) }
    editingFile?.let { file ->
        FileEditorScreen(
            file = file,
            repository = repository,
            onBack = { editingFile = null },
            onSaved = {
                editingFile = null
                vm.refresh()
            },
        )
        return
    }

    // 对话框 / 菜单 / 搜索态（本地转发，实际搜索在 ViewModel）
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var menuEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showBatchDelete by remember { mutableStateOf(false) }
    var targetMode by remember { mutableStateOf<TargetMode?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

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
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(text = { Text("新建文件") }, onClick = { showMoreMenu = false; showNewFile = true })
                                DropdownMenuItem(text = { Text("新建文件夹") }, onClick = { showMoreMenu = false; showNewFolder = true })
                                DropdownMenuItem(text = { Text("导入文件") }, onClick = { showMoreMenu = false; importLauncher.launch(arrayOf("*/*")) })
                            }
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
                FloatingActionButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "新建")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!searchActive) {
                val rootPath = remember(state.root, repository) {
                    repository.resolveRoot(state.root)?.absolutePath ?: state.currentPath
                }
                EditableBreadcrumb(
                    rootLabel = state.root.displayName(),
                    rootPath = rootPath,
                    path = state.currentPath,
                    onNavigate = { path ->
                        if (path.isBlank()) vm.navigateToInternalRoot()
                        else vm.navigateToPath(path)
                    },
                )
                ShortcutBar(
                    shortcuts = state.shortcuts,
                    currentPath = state.currentPath,
                    onNavigate = { shortcut ->
                        if (shortcut.root != null) vm.switchRoot(shortcut.root)
                        else vm.navigateToPath(shortcut.path)
                    },
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
                        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(state.entries, key = { it.path }) { entry ->
                                FileListRow(
                                    entry = entry,
                                    multiSelect = state.isMultiSelect,
                                    selected = entry.path in state.selection,
                                    onClick = {
                                        if (state.isMultiSelect) {
                                            vm.toggleSelect(entry.path)
                                        } else if (entry.isDirectory) {
                                            vm.onNavigatedTo(entry.path)
                                        } else {
                                            when (openKind(File(entry.path), repository)) {
                                                FileKind.TEXT, FileKind.MARKDOWN -> editingFile = entry
                                                FileKind.LARGE_TEXT -> scope.launch { snackbarHostState.showSnackbar("文件过大，请用终端打开") }
                                                else -> scope.launch { snackbarHostState.showSnackbar("无法预览（二进制或未知格式）") }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!state.isMultiSelect) {
                                            menuEntry = entry
                                        }
                                    },
                                )
                            }
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                androidx.compose.foundation.layout.Column {
                    TextButton(onClick = {
                        menuEntry = null
                        vm.enterMultiSelect()
                        vm.toggleSelect(target.path)
                    }, modifier = Modifier.fillMaxWidth()) { Text("多选", modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { menuEntry = null; renameTarget = target },
                        modifier = Modifier.fillMaxWidth()) { Text("重命名", modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { menuEntry = null; deleteTarget = target },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("删除", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuEntry = null }) { Text("取消") } },
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
        TargetDirPicker(
            root = state.root,
            repository = repository,
            onPick = { targetDir ->
                when (mode) {
                    TargetMode.COPY -> vm.copySelection(targetDir)
                    TargetMode.MOVE -> vm.moveSelection(targetDir)
                }
                targetMode = null
            },
            onDismiss = { targetMode = null },
        )
    }
}

/** 复制 / 移动目标模式 */
private enum class TargetMode { COPY, MOVE }

/** 搜索输入框（自动聚焦由用户点击输入触发；含清除按钮） */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("文件名包含…") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
            }
        },
    )
}

/** 搜索结果列表：显示相对路径，点击进入所在目录/文件 */
@Composable
private fun SearchResultList(
    results: List<FileSearchResult>,
    onNavigate: (String) -> Unit,
) {
    if (results.isEmpty()) {
        EmptyState(icon = Icons.Outlined.Search, title = "无搜索结果", description = "换个关键词试试")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(results, key = { it.path }) { r ->
            val kind = fileKindOf(r.name, r.isDirectory)
            val targetPath = if (r.isDirectory) r.path else File(r.path).parentFile?.absolutePath ?: r.path
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(targetPath) },
                leadingContent = { Icon(fileIcon(kind), contentDescription = null, tint = fileColor(kind)) },
                headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(r.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
