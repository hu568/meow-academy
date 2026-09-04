package com.meow.academy.ui.chat

/**
 * 右侧功能看板「工作设置」面板（plan-standard-mode §5.7）。
 *
 * 单页三栏纵向排列（每栏 weight(1f) + 内部 verticalScroll，内容少时自然留白）：
 * ① 工作区   —— 新会话默认工作区展示 + 添加/切换（FolderPickerDialog 单根 filesDir）+
 *               候选列表（filesDir 本身 + 一级子目录动态扫描）+ 当前默认工作区会话列表；
 * ② Agent 预设 —— presets/list 动态结果 + 本地占位卡合并渲染，卡片说明可折叠，
 *               trust=user 支持长按删除；
 * ③ 角色设定 —— 角色/记忆两个开关 + 角色选择器入口（plan-memory-execution §3.1）。
 *
 * 切换工作区 = 只写 DataStore（vm.switchWorkspace），不重启 DSH、不打扰生成中的会话
 * （工作区 > 会话，归属随首条消息定死，喵~）。
 */

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.model.PresetEntry
import com.meow.academy.ui.components.AppSectionHeader
import com.meow.academy.ui.files.FolderPickerDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工作区路径短名（Context 便捷重载，ChatScreen §5.10 顶栏小字与 SessionDrawer 元信息行共用，喵~）：
 * 内部取 `context.filesDir` 后走主规则。
 */
fun workspaceShortName(path: String?, context: android.content.Context): String =
    workspaceShortName(path, context.filesDir.absolutePath)

/**
 * 工作区路径短名（本面板内的小函数，会话抽屉元信息行同规则复用，喵~）：
 * `<filesDir>/workspace` → 「workspace」、`<filesDir>` 本身 → 「files」、其余取最后一段文件夹名。
 * path 为 null（旧数据未写工作区）时按历史唯一工作区回退「workspace」。
 */
fun workspaceShortName(path: String?, filesDirPath: String): String {
    if (path == null) return "workspace"
    val normalized = path.trimEnd('/')
    val root = filesDirPath.trimEnd('/')
    return when {
        normalized == root -> "files"
        normalized == "$root/workspace" -> "workspace"
        else -> normalized.substringAfterLast('/')
    }
}

/** 右侧看板「工作设置」面板入口（ChatScreen 的 DashboardDrawer.workspaceSettingsPanel 槽位接线用） */
@Composable
fun WorkspaceSettingsPanel(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        WorkspaceSection(vm = vm, modifier = Modifier.weight(1f))
        HorizontalDivider()
        AgentPresetSection(vm = vm, modifier = Modifier.weight(1f))
        HorizontalDivider()
        PersonaSettingsSection(vm = vm, modifier = Modifier.weight(1f))
    }
}

// ─────────────────────────── ① 工作区栏 ───────────────────────────

/** ① 工作区：默认工作区行 + 添加/切换 + 候选列表 + 当前默认工作区会话列表 */
@Composable
private fun WorkspaceSection(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val filesDirPath = context.filesDir.absolutePath
    val sessions by vm.sessions.collectAsState()
    val defaultWorkspacePath by vm.defaultWorkspacePath.collectAsState()

    var showPicker by remember { mutableStateOf(false) }
    // 候选工作区：filesDir 本身 + 一级子目录（java.io.File 动态扫描，不持久化候选，喵~）
    var candidates by remember { mutableStateOf<List<File>>(emptyList()) }

    // 扫描候选（showPicker 变化时重扫：对话框里新建的文件夹关闭后即刻出现）
    LaunchedEffect(showPicker) {
        candidates = withContext(Dispatchers.IO) {
            val dir = File(filesDirPath)
            val subs = dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith('.') }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            listOf(dir) + subs
        }
    }

    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    // 切换候选/选中目录 → 只写 DataStore（新会话生效），轻提示即可（不重启、无中断，喵~）
    val pickWorkspace: (String) -> Unit = { path ->
        vm.switchWorkspace(path)
        toast("新会话将工作在 ${workspaceShortName(path, filesDirPath)} 喵~")
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        AppSectionHeader("工作区")

        // 「新会话默认工作区」行：路径短名 + 副注
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = workspaceShortName(defaultWorkspacePath, filesDirPath),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "新会话将工作在此目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 「添加工作区」按钮 → 单根 filesDir 目录选择对话框
        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        ) {
            Icon(
                Icons.Filled.CreateNewFolder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("添加工作区")
        }

        // 候选列表：当前默认项高亮；点击 = 设为新会话默认工作区
        candidates.forEach { dir ->
            val path = dir.absolutePath
            val selected = path == defaultWorkspacePath
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .clickable { pickWorkspace(path) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (path == filesDirPath) "files（根目录）" else dir.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "当前默认工作区",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 下半部：当前默认工作区的会话列表（标题 + 时间简化行，点击打开）
        Text(
            text = "本工作区的会话",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp),
        )
        val workspaceSessions = sessions.filter { it.workspacePath == defaultWorkspacePath }
        if (workspaceSessions.isEmpty()) {
            Text(
                text = "本工作区还没有会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        } else {
            workspaceSessions.forEach { session ->
                WorkspaceSessionRow(session = session, onOpen = { vm.openSession(session.id) })
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showPicker) {
        FolderPickerDialog(
            title = "选择工作区（可新建文件夹喵）",
            confirmLabel = "设为默认",
            initialDir = filesDirPath,
            // 根约束 = filesDir 单根：浏览/路径输入/返回键都被限制在 files 内
            roots = listOf(filesDirPath),
            onPick = { path ->
                showPicker = false
                pickWorkspace(path)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 当前默认工作区会话列表的简化行（标题 + 紧凑时间），点击打开会话 */
@Composable
private fun WorkspaceSessionRow(session: SessionEntity, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatSessionTimestamp(session.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────── ② Agent 预设栏 ───────────────────────────

/** ② Agent 预设：presets/list 动态渲染（占位卡机制已随四预设全部播种退役），卡片说明可折叠，trust=user 长按删除 */
@Composable
private fun AgentPresetSection(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val presetCatalog by vm.presetCatalog.collectAsState()
    val defaultPreset by vm.defaultPreset.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()

    // 进面板刷新一次 presets/list（DSH 侧无推送事件，触发时机归 UI 层，喵~）
    LaunchedEffect(Unit) { vm.refreshPresets() }

    val entries = presetCatalog

    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingPreset by remember { mutableStateOf<PresetEntry?>(null) }

    // 会话已有消息（或在生成）→ 预设已锁定；空白会话首条消息前可自由切换（与角色设定同款判定）
    val locked = currentSession != null && (messages.isNotEmpty() || streaming != null)
    val blankSessionOpen = currentSession != null && !locked
    // 当前会话实际归属的预设（优先会话行；未开会话回退新会话默认）
    val currentPresetId = currentSession?.presetId?.takeIf { it.isNotBlank() } ?: defaultPreset

    Column(modifier.verticalScroll(rememberScrollState())) {
        AppSectionHeader("Agent 预设")
        Text(
            text = when {
                locked -> "当前会话已锁定预设，新会话可自由切换喵~"
                blankSessionOpen -> "切换将应用到当前新会话，并设为新会话默认喵~"
                else -> "预设决定新会话的能力组合，仅对新会话生效喵"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
        )
        entries.forEach { entry ->
            val disabled = entry.broken != null
            val isCurrent = entry.id == currentPresetId && !disabled
            val isDefault = entry.id == defaultPreset && !disabled
            PresetCard(
                entry = entry,
                isCurrent = isCurrent,
                isDefault = isDefault,
                locked = locked,
                blankSessionOpen = blankSessionOpen,
                disabled = disabled,
                expanded = entry.id in expandedIds,
                onToggleExpand = {
                    expandedIds = if (entry.id in expandedIds) expandedIds - entry.id else expandedIds + entry.id
                },
                onSelect = { vm.selectDefaultPreset(entry.id) },
                onRequestDelete = { deletingPreset = entry },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    deletingPreset?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text("删除预设") },
            text = { Text("确定删除自定义预设「${entry.name ?: entry.id}」吗？此操作不可撤销喵。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePreset(entry.id)
                    deletingPreset = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingPreset = null }) { Text("取消") } },
        )
    }
}

/**
 * 单张预设卡片（同原版 DSH「名称 + 描述」展示风格，喵~）：
 * - 卡片头：名称（name 缺省回退 id）+ 右侧当前/默认高亮 ✓（可选卡行尾为「设为默认」动作）；
 * - 说明可折叠：默认一行 ellipsis，点卡片展开完整说明；
 * - broken 预设灰显 + 展开后显示原因，标注「不可用」不可选；
 * - trust == "user" 的自定义预设长按删除（确认框）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(
    entry: PresetEntry,
    /** 当前会话（或当前空白新会话）实际归属的预设 */
    isCurrent: Boolean,
    /** 新会话默认预设（DataStore） */
    isDefault: Boolean,
    /** 会话已有消息 → 预设已锁定，切换只影响新会话 */
    locked: Boolean,
    /** 当前打开的是空白新会话 → 切换会同步到该会话行 */
    blankSessionOpen: Boolean,
    disabled: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val containerColor = when {
        isCurrent || isDefault -> MaterialTheme.colorScheme.primaryContainer
        disabled -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onToggleExpand,
                // 仅 trust=user 的真实自定义预设支持长按删除（内置不可删，喵~）
                onLongClick = if (!disabled && entry.trust == "user") onRequestDelete else null,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 卡片头：名称 + 右侧状态/动作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name ?: entry.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    isCurrent && isDefault -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "当前·默认预设",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "当前·默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    isCurrent -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "当前会话预设",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    isDefault && blankSessionOpen -> {
                        Text(
                            text = "应用到当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onSelect)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    isDefault -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "当前默认预设",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "默认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    !disabled -> Text(
                        text = "设为默认",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSelect)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    else -> Text(
                        text = "不可用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            // 说明：默认一行 ellipsis，点卡片展开完整说明
            entry.description?.let { description ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // broken 预设：展开后显示解析失败原因
            entry.broken?.let { reason ->
                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "解析失败：$reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 行尾注明：只有带「设为默认 / 应用到当前」动作的卡才显示（纯当前/纯默认卡无动作，不重复说明，喵~）
            if (!disabled && !isCurrent && (!isDefault || blankSessionOpen)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        locked -> "切换只影响新会话"
                        blankSessionOpen -> "将应用到当前新会话"
                        else -> "对新会话生效"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ─────────────────────────── ③ 角色设定栏 ───────────────────────────

/**
 * ③ 角色设定（plan-memory-execution §3.1）：两个会话级开关 + 角色选择器入口。
 *
 * - [角色开关] ON → 注入 <soul>/<user>；[记忆开关] ON → 注入 <facts>/存储契约并挂 memory 工具；
 *   两者默认 ON，改动写 DataStore（新会话默认）并同步当前空白会话行；
 * - 角色开关 OFF → 「打开角色选择器」按钮**灰掉不可点**（design §2.3：不弹窗、personaId 不绑定）；
 * - 当前会话已有消息 → 角色锁定，选择器以只读模式打开（§3.4）。
 */
@Composable
private fun PersonaSettingsSection(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val personaCatalog by vm.personaCatalog.collectAsState()
    val defaultPersonaId by vm.defaultPersonaId.collectAsState()
    val personaEnabled by vm.personaEnabled.collectAsState()
    val memoryEnabled by vm.memoryEnabled.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()

    var showPicker by remember { mutableStateOf(false) }

    // 进面板刷新一次 personas/list（DSH 侧无推送事件，触发时机归 UI 层，喵~）
    LaunchedEffect(Unit) { vm.refreshPersonas() }

    // 当前会话锁定的角色（优先）；未开会话时显示默认角色
    val boundPersonaId = currentSession?.personaId?.takeIf { it.isNotBlank() } ?: defaultPersonaId
    val boundName = personaCatalog.firstOrNull { it.id == boundPersonaId }?.name ?: boundPersonaId

    Column(modifier.verticalScroll(rememberScrollState())) {
        AppSectionHeader("角色设定")
        Text(
            text = "两个开关与会话绑定，首条消息后锁定喵~",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
        )

        // 角色开关
        SwitchRow(
            title = "角色",
            subtitle = if (personaEnabled) "注入 <soul> + <user> 人格设定" else "不注入任何人格内容",
            checked = personaEnabled,
            onCheckedChange = vm::setPersonaEnabled,
        )
        // 记忆开关
        SwitchRow(
            title = "记忆",
            subtitle = if (memoryEnabled) "注入 <facts> + 存储契约，并挂 memory 工具" else "不注入事实、无 memory 工具",
            checked = memoryEnabled,
            onCheckedChange = vm::setMemoryEnabled,
        )

        Spacer(Modifier.height(6.dp))

        // 当前角色名 + 选择器入口（角色开关 OFF → 灰掉不可点）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (personaEnabled) boundName.ifBlank { "未选择角色" } else "（角色已关闭）",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !personaEnabled -> "开关打开后可选择角色"
                        currentSession != null && messages.isNotEmpty() -> "当前会话已锁定该角色"
                        else -> "新会话默认使用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            OutlinedButton(
                onClick = { showPicker = true },
                enabled = personaEnabled,
            ) { Text("选择角色") }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showPicker) {
        // 会话已有消息（或在生成）→ 选择器只读：显示锁定角色但不可切（§3.4）
        val locked = currentSession != null && (messages.isNotEmpty() || streaming != null)
        PersonaPickerDialog(
            personas = personaCatalog,
            selectedId = boundPersonaId.takeIf { it.isNotBlank() },
            locked = locked,
            onDismiss = { showPicker = false },
            onSelect = { id ->
                vm.selectDefaultPersona(id)
                showPicker = false
            },
            onCreate = vm::createPersona,
            onDelete = vm::deletePersona,
            onReorder = vm::reorderPersonas,
        )
    }
}

/** 开关行（标题 + 副注 + 右侧 Switch），两开关各占一行（§3.1） */
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
