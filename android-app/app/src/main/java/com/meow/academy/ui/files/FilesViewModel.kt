package com.meow.academy.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.data.files.FileEntry
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

/**
 * 文件管理页 UI 状态。
 *
 * 字段名对 UI 层固定；操作反馈统一走 [snackbarMessage]，UI 展示后调 [FilesViewModel.consumeSnackbar] 清空。
 */
data class FilesUiState(
    val root: FileRoot = FileRoot.INTERNAL,
    val currentPath: String = "",               // 当前目录绝对路径
    val entries: List<FileEntry> = emptyList(),
    val sortMode: FileSortMode = FileSortMode.DEFAULT,
    val sortAscending: Boolean = true,
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
 * 职责：根切换、目录栈、多模式排序、多选、搜索（防抖 300ms）、增删改查操作调度。
 * 所有文件 IO 均走 [FileRepository]（内部已切 Dispatchers.IO），本类不再自行碰主线程阻塞。
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileRepository(app)

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

    /** 切换根；EXTERNAL 不可用时忽略，同时清空搜索与多选态 */
    fun switchRoot(root: FileRoot) {
        if (root == FileRoot.EXTERNAL && repository.resolveRoot(FileRoot.EXTERNAL) == null) return
        if (root == _uiState.value.root) return
        val stack = stacks.getOrPut(root) {
            ArrayDeque<String>().apply { repository.resolveRoot(root)?.let { add(it.absolutePath) } }
        }
        val target = stack.lastOrNull() ?: return
        _uiState.update {
            it.copy(
                root = root,
                currentPath = target,
                isMultiSelect = false,
                selection = emptySet(),
                isSearching = false,
                searchQuery = "",
                searchResults = emptyList(),
            )
        }
        refresh()
    }

    // ── 列表加载与排序 ──

    /** 重新加载当前目录列表（IO 线程），按当前排序规则排序 */
    fun refresh() {
        val path = _uiState.value.currentPath
        if (path.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val entries = withContext(Dispatchers.IO) { repository.listDirectory(path) }
                val sorted = sortEntries(entries, _uiState.value.sortMode, _uiState.value.sortAscending)
                _uiState.update { it.copy(entries = sorted, isLoading = false) }
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

    /** 重命名（校验新名） */
    fun rename(path: String, newName: String) {
        if (!repository.isValidName(newName)) {
            showSnackbar("文件名不合法")
            return
        }
        viewModelScope.launch {
            val ok = repository.rename(path, newName)
            if (ok) {
                refresh()
                showSnackbar("已重命名")
            } else {
                showSnackbar("重命名失败")
            }
        }
    }

    /** 删除单个文件 / 文件夹 */
    fun delete(path: String) {
        viewModelScope.launch {
            val ok = repository.delete(path)
            if (ok) {
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

    /** 移动选中的文件到目标目录，成功后刷新并退出多选 */
    fun moveSelection(targetDir: String) {
        val selection = _uiState.value.selection.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch {
            val ok = repository.move(selection, targetDir)
            exitMultiSelect()
            if (ok) {
                refresh()
                showSnackbar("已移动 ${selection.size} 项")
            } else {
                showSnackbar("移动失败")
            }
        }
    }

    /** 批量删除选中的文件（确认弹框由 UI 层负责），逐项删除后刷新并退出多选 */
    fun deleteSelection() {
        val selection = _uiState.value.selection.toList()
        if (selection.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            for (path in selection) {
                if (!repository.delete(path)) failed++
            }
            exitMultiSelect()
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
            val rootPath = repository.resolveRoot(_uiState.value.root)?.absolutePath ?: return@launch
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
}
