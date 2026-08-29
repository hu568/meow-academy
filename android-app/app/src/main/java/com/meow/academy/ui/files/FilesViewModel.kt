package com.meow.academy.ui.files

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.data.files.FileEntry
import com.meow.academy.data.files.FileFavoritesRepository
import com.meow.academy.data.files.FileRecentRepository
import com.meow.academy.data.files.FileRepository
import com.meow.academy.data.files.FileRoot
import com.meow.academy.data.files.FileSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 文件列表排序模式 */
enum class FileSortMode { DEFAULT, NAME, SIZE, MODIFIED }

/** 文件视图显示方式：列表（一行一项）/ 宫格（一行三项）/ 瀑布流（一行两项卡片）（喵~） */
enum class FileViewMode { LIST, GRID, WATERFALL }

/**
 * 文件管理页 UI 状态。
 *
 * 字段名对 UI 层固定；操作反馈统一走 [snackbarMessage]，UI 展示后调 [FilesViewModel.consumeSnackbar] 清空。
 */
data class FilesUiState(
    val root: FileRoot = FileRoot.INTERNAL,
    val currentPath: String = "",               // 当前目录绝对路径
    val entries: List<FileEntry> = emptyList(),
    val favoritePaths: List<String> = emptyList(), // 收藏的绝对路径（收藏顺序，长按菜单判断是否已收藏用）
    val favorites: List<FileEntry> = emptyList(),  // 收藏抽屉展示条目（文件已删的自动剔除）
    val recents: List<FileEntry> = emptyList(),    // 最近使用展示条目（最新在前，文件已删的自动剔除）
    val sortMode: FileSortMode = FileSortMode.DEFAULT,
    val sortAscending: Boolean = true,
    val viewMode: FileViewMode = FileViewMode.LIST, // 视图显示方式（右上角更多菜单切换）
    val showHiddenFiles: Boolean = false,       // 是否显示 . 开头的隐藏文件（Linux 习惯，默认隐藏）
    val isMultiSelect: Boolean = false,
    val selection: Set<String> = emptySet(),    // 已选文件的绝对路径
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<FileSearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val externalAvailable: Boolean = true,      // getExternalFilesDir 是否为 null
    val snackbarMessage: String? = null,        // 操作反馈（UI 消费后调 consumeSnackbar()）
)

/**
 * 📁 文件管理页 ViewModel（M4.2）。
 *
 * 职责：根切换、目录栈、多模式排序、视图切换（列表/宫格/瀑布流）、多选、搜索（防抖 300ms）、
 * 收藏（快捷方式抽屉）、增删改查操作调度。
 * 所有文件 IO 均走 [FileRepository]（内部已切 Dispatchers.IO），本类不再自行碰主线程阻塞。
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileRepository(app)
    private val favoritesRepository = FileFavoritesRepository(app)
    private val recentsRepository = FileRecentRepository(app)

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    /** 每个根独立的目录栈，栈底是根路径 */
    private val stacks = mutableMapOf<FileRoot, ArrayDeque<String>>()

    /** 搜索防抖 Job（300ms） */
    private var searchJob: Job? = null

    init {
        // currentPath 指向 INTERNAL 根；externalAvailable 由 EXTERNAL 是否可解析决定
        val internalRoot = repository.resolveRoot(FileRoot.INTERNAL)!!.absolutePath
        stacks[FileRoot.INTERNAL] = ArrayDeque<String>().apply { add(internalRoot) }
        _uiState.update {
            it.copy(
                currentPath = internalRoot,
                externalAvailable = repository.resolveRoot(FileRoot.EXTERNAL) != null,
            )
        }
        refresh()

        // 收藏 / 最近数据流：存储变化 → 解析为展示条目（文件已删的剔除）并同步进 UI 状态
        viewModelScope.launch {
            favoritesRepository.favoritePaths.collect { paths ->
                val resolved = resolveShortcutEntries(paths)
                _uiState.update { it.copy(favoritePaths = paths, favorites = resolved) }
            }
        }
        viewModelScope.launch {
            recentsRepository.recentPaths.collect { paths ->
                _uiState.update { it.copy(recents = resolveShortcutEntries(paths)) }
            }
        }
    }

    // ── 导航 ──

    /** 进入目录（列表页点击文件夹 / 搜索结果跳转）；path 是文件时忽略（打开文件由 UI 层回调处理） */
    fun onNavigatedTo(path: String) {
        if (path.isBlank()) return
        val root = _uiState.value.root
        viewModelScope.launch {
            val isDirectory = withContext(Dispatchers.IO) { File(path).isDirectory }
            if (!isDirectory) return@launch
            val stack = stacks.getOrPut(root) {
                ArrayDeque<String>().apply { repository.resolveRoot(root)?.let { add(it.absolutePath) } }
            }
            if (stack.lastOrNull() != path) stack.addLast(path)
            _uiState.update { it.copy(currentPath = path) }
            refresh()
        }
    }

    /** 返回上级目录；已在根目录时 no-op */
    fun navigateUp() {
        val root = _uiState.value.root
        val stack = stacks[root] ?: return
        if (stack.size <= 1) return
        stack.removeLast()
        _uiState.update { it.copy(currentPath = stack.last()) }
        refresh()
    }

    /** 显式切根（快捷栏根项用）：总是跳到该根目录本身，并重置该根的目录栈 */
    fun switchRoot(root: FileRoot) {
        if (root == FileRoot.EXTERNAL && repository.resolveRoot(FileRoot.EXTERNAL) == null) return
        val rootPath = repository.resolveRoot(root)?.absolutePath ?: return
        stacks[root] = ArrayDeque<String>().apply { add(rootPath) }
        _uiState.update {
            it.copy(
                root = root,
                currentPath = rootPath,
                isMultiSelect = false,
                selection = emptySet(),
                isSearching = false,
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
        refresh()
    }

    /** 面包屑输入为空时回工作区 */
    fun navigateToInternalRoot() = switchRoot(FileRoot.INTERNAL)

    // ── 列表加载与排序 ──

    /** 重新加载当前目录列表（IO 线程），按当前排序规则排序，同时重算收藏条目存在性 */
    fun refresh() {
        val path = _uiState.value.currentPath
        if (path.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val (entries, favoriteEntries) = withContext(Dispatchers.IO) {
                    repository.listDirectory(path) to resolveShortcutEntries(favoritesRepository.current())
                }
                // 隐藏文件过滤（Linux 习惯：. 开头不显示）
                val visible = if (_uiState.value.showHiddenFiles) entries else entries.filterNot { it.name.startsWith('.') }
                val sorted = sortEntries(visible, _uiState.value.sortMode, _uiState.value.sortAscending)
                _uiState.update { it.copy(entries = sorted, favorites = favoriteEntries, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "加载失败",
                        snackbarMessage = e.message ?: "加载失败",
                    )
                }
            }
        }
    }

    /** 快捷栏 / 面包屑 / 输入路径跳转：校验目录存在且在可用根内，重建该根的目录栈 */
    fun navigateToPath(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            // 归一化：canonical 只做安全判定，存储统一映射回与根解析一致的 absolutePath 形式，
            // 避免 /data/user/0 ↔ /data/data symlink 差异污染 currentPath（喵~）
            val normalized = withContext(Dispatchers.IO) {
                repository.normalizeNavigationPath(path)
            } ?: run {
                Log.w(TAG, "navigateToPath 拒绝（不存在或非目录）: [$path]")
                showSnackbar("路径不存在或不是文件夹")
                return@launch
            }
            Log.d(TAG, "navigateToPath: [$path] -> [$normalized]")
            val root = repository.resolveRootForPath(normalized) ?: run {
                Log.w(TAG, "navigateToPath 拒绝（不在可用根内）: [$normalized]")
                showSnackbar("路径不在可用目录内")
                return@launch
            }
            stacks[root] = buildStack(root, normalized)
            _uiState.update {
                it.copy(
                    root = root,
                    currentPath = normalized,
                    isMultiSelect = false,
                    selection = emptySet(),
                    isSearching = false,
                    searchQuery = "",
                    searchResults = emptyList(),
                )
            }
            refresh()
        }
    }

    /** 从栈底到目标路径重建目录栈（用于任意跳转；INTERNAL 时栈底可能是 workspace 或 filesDir） */
    private fun buildStack(root: FileRoot, path: String): ArrayDeque<String> {
        val base = repository.stackBaseFor(root, path) ?: return ArrayDeque()
        val stack = ArrayDeque<String>().apply { add(base) }
        var current = base
        val relative = path.removePrefix(base).trim('/')
        if (relative.isEmpty()) return stack
        relative.split('/').filter { it.isNotEmpty() }.forEach { part ->
            current = if (current == "/") "/$part" else "$current/$part"
            stack.add(current)
        }
        return stack
    }

    /** 收藏/最近路径 → 展示条目（IO 线程探测存在性；文件已删除的条目自动剔除，喵~） */
    private suspend fun resolveShortcutEntries(paths: List<String>): List<FileEntry> =
        withContext(Dispatchers.IO) {
            paths.mapNotNull { path ->
                val file = File(path)
                if (file.exists()) {
                    FileEntry(file.name, path, file.isDirectory, file.length(), file.lastModified())
                } else {
                    null
                }
            }
        }

    // ── 收藏（快捷方式抽屉） ──

    /** 收藏 / 取消收藏（长按菜单、抽屉长按取消共用）；新收藏置顶，收起时优先露出最近收藏（喵~） */
    fun toggleFavorite(path: String) {
        viewModelScope.launch {
            val current = favoritesRepository.current()
            val wasFavorite = path in current
            val next = if (wasFavorite) current - path else listOf(path) + current
            favoritesRepository.setFavorites(next)
            showSnackbar(if (wasFavorite) "已取消收藏" else "已收藏")
        }
    }

    /** 按转换函数更新收藏存储：重命名/移动/删除后同步收藏路径，避免收藏指向失效位置 */
    private fun updateFavorites(transform: (List<String>) -> List<String>) {
        viewModelScope.launch {
            favoritesRepository.setFavorites(transform(favoritesRepository.current()))
        }
    }

    // ── 最近使用 ──

    /** 记录一次文件使用（打开/附加），数据流自动刷新「最近使用」列表（喵~） */
    fun recordRecent(path: String) {
        viewModelScope.launch { recentsRepository.record(path) }
    }

    /** 移除单条最近使用记录（「最近使用」长按删除用） */
    fun removeRecent(path: String) {
        viewModelScope.launch { recentsRepository.remove(path) }
    }

    /** 切排序模式：更新状态并按新规则就地重排当前 entries */
    fun setSortMode(mode: FileSortMode, ascending: Boolean) {
        _uiState.update {
            it.copy(
                sortMode = mode,
                sortAscending = ascending,
                entries = sortEntries(it.entries, mode, ascending),
            )
        }
    }

    /** 切换是否显示隐藏文件（. 开头，Linux 习惯）：更新状态并重新加载当前目录 */
    fun toggleShowHidden() {
        _uiState.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        refresh()
    }

    /** 切换视图显示方式（列表 / 宫格 / 瀑布流）：只影响展示形态，不动数据 */
    fun setViewMode(mode: FileViewMode) = _uiState.update { it.copy(viewMode = mode) }

    /**
     * 排序：文件夹永远优先（先分组再排序），升/降序只影响组内顺序。
     * DEFAULT/NAME 按名称（忽略大小写）；SIZE 按大小（目录按 0 排）；MODIFIED 按修改时间。
     */
    private fun sortEntries(entries: List<FileEntry>, mode: FileSortMode, ascending: Boolean): List<FileEntry> {
        val dirs = entries.filter { it.isDirectory }
        val files = entries.filter { !it.isDirectory }
        fun sortGroup(group: List<FileEntry>): List<FileEntry> {
            val sorted = when (mode) {
                FileSortMode.NAME, FileSortMode.DEFAULT -> group.sortedBy { it.name.lowercase() }
                FileSortMode.SIZE -> group.sortedBy { if (it.isDirectory) 0L else it.size }
                FileSortMode.MODIFIED -> group.sortedBy { it.lastModified }
            }
            return if (ascending) sorted else sorted.reversed()
        }
        return sortGroup(dirs) + sortGroup(files)
    }

    // ── 多选 ──

    /** 进入多选模式 */
    fun enterMultiSelect() = _uiState.update { it.copy(isMultiSelect = true) }

    /** 退出多选模式并清空选中 */
    fun exitMultiSelect() = _uiState.update { it.copy(isMultiSelect = false, selection = emptySet()) }

    /** 勾选 / 取消勾选指定路径 */
    fun toggleSelect(path: String) {
        _uiState.update { state ->
            val selection = if (path in state.selection) state.selection - path else state.selection + path
            state.copy(selection = selection)
        }
    }

    // ── 文件操作（增删改查） ──

    /** 在当前目录新建文件 */
    fun createFile(name: String) {
        if (!repository.isValidName(name)) {
            showSnackbar("文件名不合法")
            return
        }
        val parent = _uiState.value.currentPath
        viewModelScope.launch {
            val ok = repository.createFile(parent, name)
            if (ok) {
                refresh()
                showSnackbar("已创建文件")
            } else {
                showSnackbar("创建失败")
            }
        }
    }

    /** 在当前目录新建文件夹 */
    fun createFolder(name: String) {
        if (!repository.isValidName(name)) {
            showSnackbar("文件夹名不合法")
            return
        }
        val parent = _uiState.value.currentPath
        viewModelScope.launch {
            val ok = repository.createDirectory(parent, name)
            if (ok) {
                refresh()
                showSnackbar("已创建文件夹")
            } else {
                showSnackbar("创建失败")
            }
        }
    }

    /** 重命名（校验新名）；收藏中的旧路径同步改指新路径 */
    fun rename(path: String, newName: String) {
        if (!repository.isValidName(newName)) {
            showSnackbar("文件名不合法")
            return
        }
        viewModelScope.launch {
            val ok = repository.rename(path, newName)
            if (ok) {
                val renamedPath = File(path).parentFile?.let { File(it, newName).absolutePath } ?: path
                updateFavorites { list -> list.map { if (it == path) renamedPath else it } }
                refresh()
                showSnackbar("已重命名")
            } else {
                showSnackbar("重命名失败")
            }
        }
    }

    /** 删除单个文件 / 文件夹；从收藏中同步移除 */
    fun delete(path: String) {
        viewModelScope.launch {
            val ok = repository.delete(path)
            if (ok) {
                updateFavorites { list -> list - path }
                refresh()
                showSnackbar("已删除")
            } else {
                showSnackbar("删除失败")
            }
        }
    }

    // ── 批量操作 ──

    /** 复制选中的文件到目标目录，成功后刷新并退出多选 */
    fun copySelection(targetDir: String) {
        val selection = _uiState.value.selection.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val ok = repository.copy(selection, targetDir)
            exitMultiSelect()
            if (ok) {
                refresh()
                showSnackbar("已复制 ${selection.size} 项")
            } else {
                showSnackbar("复制失败")
            }
        }
    }

    /** 移动选中的文件到目标目录，成功后刷新并退出多选；收藏中的旧路径同步改指新位置 */
    fun moveSelection(targetDir: String) {
        val selection = _uiState.value.selection.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val ok = repository.move(selection, targetDir)
            exitMultiSelect()
            if (ok) {
                val movedTo = selection.associateWith { File(targetDir, File(it).name).absolutePath }
                updateFavorites { list -> list.map { movedTo[it] ?: it } }
                refresh()
                showSnackbar("已移动 ${selection.size} 项")
            } else {
                showSnackbar("移动失败")
            }
        }
    }

    /** 批量删除选中的文件（确认弹框由 UI 层负责），逐项删除后刷新并退出多选；收藏同步移除 */
    fun deleteSelection() {
        val selection = _uiState.value.selection.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            for (path in selection) {
                if (!repository.delete(path)) failed++
            }
            exitMultiSelect()
            updateFavorites { list -> list - selection.toSet() }
            refresh()
            showSnackbar(if (failed == 0) "已删除 ${selection.size} 项" else "删除失败 $failed 项")
        }
    }

    /** 经 SAF 导入文件到当前目录，统计成功数后刷新 */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val targetDir = _uiState.value.currentPath
        viewModelScope.launch {
            val results = repository.importFromUris(uris, targetDir)
            val success = results.count { it }
            refresh()
            showSnackbar(if (success > 0) "已导入 $success 个文件" else "导入失败")
        }
    }

    // ── 搜索 ──

    /**
     * 搜索输入变化：防抖 300ms 后执行（新查询先 cancel 上一个 Job）；
     * 查询为空则退出搜索态。搜索在整个当前根下递归（root 参数 = 根绝对路径，不是 currentPath）。
     */
    fun onSearchQueryChange(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(isSearching = false, searchQuery = "", searchResults = emptyList()) }
            return
        }
        _uiState.update { it.copy(isSearching = true, searchQuery = trimmed) }
        searchJob = viewModelScope.launch {
            delay(300)
            // 搜索范围跟随当前路径实际所属的栈底（workspace 或 filesDir 系统目录区域），
            // 与面包屑基准一致；解析不出时退回当前根
            val rootPath = repository.stackBaseFor(_uiState.value.root, _uiState.value.currentPath)
                ?: repository.resolveRoot(_uiState.value.root)?.absolutePath
                ?: return@launch
            try {
                val results = withContext(Dispatchers.IO) { repository.search(rootPath, trimmed) }
                // 丢弃过期结果：查询已变化（或已清空）时不应用
                if (_uiState.value.searchQuery == trimmed) {
                    _uiState.update { it.copy(searchResults = results) }
                }
            } catch (e: Exception) {
                showSnackbar("搜索失败：${e.message}")
            }
        }
    }

    /** 退出搜索并清空结果 */
    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false, searchQuery = "", searchResults = emptyList()) }
    }

    // ── 反馈 ──

    /** 消费 snackbar 消息（UI 展示后调用，置 null） */
    fun consumeSnackbar() = _uiState.update { it.copy(snackbarMessage = null) }

    private fun showSnackbar(message: String) = _uiState.update { it.copy(snackbarMessage = message) }

    companion object {
        private const val TAG = "FilesNav"
    }
}
