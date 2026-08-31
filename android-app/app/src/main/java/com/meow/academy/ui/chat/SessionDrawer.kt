package com.meow.academy.ui.chat

/**
 * 会话抽屉薄壳：状态自持（多选组+对话框组）+ ModalDrawerSheet 骨架 + 4 分片挂载。
 * 纯展示组件：sessionFilter/defaultWorkspacePath/presetCatalog/onFilterChange 由调用方注入，
 * 不再 viewModel() 隐式抓全局 ChatViewModel（去耦合，喵~）。
 * 分片：SessionDrawerToolbar / SessionDrawerList / SessionDialogs / SessionRow。
 */
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.model.PresetEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** epoch millis → 紧凑时间（plan-standard-mode §5.8，去秒）；WorkspaceSettingsPanel 同用 */
internal fun formatSessionTimestamp(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val sameYear = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val sameDay = sameYear && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        sameYear -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(millis))
    }
}

/** 会话抽屉薄壳：状态自持，数据与回调全注入 */
@Composable
fun SessionDrawer(
    sessions: List<SessionEntity>,
    currentId: Long?,
    drawerOpen: Boolean,
    sessionFilter: String,                    // 注入：过滤档（"all"/"workspace"）
    defaultWorkspacePath: String,             // 注入：当前工作区路径
    presetCatalog: List<PresetEntry>,         // 注入：预设目录（元信息行预设名用）
    onFilterChange: (String) -> Unit,         // 注入：切换过滤档（原 chatVm.setSessionFilter）
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (SessionEntity) -> Unit,
    onDeleteMany: (List<SessionEntity>) -> Unit,
    onRename: (Long, String) -> Unit,
) {
    // 对话框组状态
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    var batchDeleting by remember { mutableStateOf(false) }
    val filesDirPath = LocalContext.current.filesDir.absolutePath
    // 会话过滤派生
    val filteredSessions = remember(sessions, sessionFilter, defaultWorkspacePath) {
        if (sessionFilter == "workspace") sessions.filter { it.workspacePath == defaultWorkspacePath }
        else sessions
    }
    // 多选组状态
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val exitSelection: () -> Unit = { selectionMode = false; selectedIds = emptySet() }
    // 抽屉关闭退出多选
    LaunchedEffect(drawerOpen) { if (!drawerOpen) exitSelection() }
    val toggleSelected: (Long) -> Unit = { id ->
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    val selectedSessions by remember(sessions, selectedIds) {
        derivedStateOf { sessions.filter { it.id in selectedIds } }
    }
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 340.dp).statusBarsPadding().navigationBarsPadding(),
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f),
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SessionDrawerToolbar(
                selectionMode = selectionMode, selectedCount = selectedIds.size,
                deleteEnabled = selectedSessions.isNotEmpty(), sessionFilter = sessionFilter,
                onFilterSelect = onFilterChange, onExitSelection = exitSelection,
                onDeleteSelected = { batchDeleting = true },
                onEnterSelection = { selectionMode = true; selectedIds = emptySet() },
                onNew = onNew,
            )
            SessionDrawerList(
                sessions = filteredSessions, hasAnySession = sessions.isNotEmpty(),
                currentId = currentId, selectionMode = selectionMode, selectedIds = selectedIds,
                sessionFilter = sessionFilter, defaultWorkspacePath = defaultWorkspacePath,
                presetCatalog = presetCatalog, filesDirPath = filesDirPath,
                onTap = { id -> if (selectionMode) toggleSelected(id) else onOpen(id) },
                onSwipeRightTrigger = { id ->
                    if (!selectionMode) { selectionMode = true; selectedIds = setOf(id) }
                    else toggleSelected(id)
                },
                onEdit = { renaming = it }, onDelete = { deleting = it },
            )
        }
    }
    SessionDialogs(
        renaming = renaming, deleting = deleting, batchDeleting = batchDeleting,
        selectedSessions = selectedSessions,
        onRenameConfirm = onRename, onDeleteConfirm = onDelete,
        onDeleteManyConfirm = { val t = selectedSessions; exitSelection(); onDeleteMany(t) },
        onDismiss = { renaming = null; deleting = null; batchDeleting = false },
    )
}