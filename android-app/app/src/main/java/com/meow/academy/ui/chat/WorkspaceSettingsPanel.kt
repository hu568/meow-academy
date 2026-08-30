package com.meow.academy.ui.chat

/**
 * 右侧功能看板「工作设置」面板（plan-standard-mode §5.7）。
 *
 * 单页三栏纵向排列（每栏 weight(1f) + 内部 verticalScroll，内容少时自然留白）：
 * ① 工作区   —— 新会话默认工作区展示 + 添加/切换（FolderPickerDialog 单根 filesDir）+
 *               候选列表（filesDir 本身 + 一级子目录动态扫描）+ 当前默认工作区会话列表；
 * ② Agent 预设 —— presets/list 动态结果 + 本地占位卡合并渲染，卡片说明可折叠，
 *               trust=user 支持长按删除；
 * ③ 记忆/角色 —— 本版只做角色展示（喵喵老师，唯一不可切换），记忆功能占位。
 *
 * 切换工作区 = 只写 DataStore（vm.switchWorkspace），不重启 DSH、不打扰生成中的会话
 * （工作区 > 会话，归属随首条消息定死，喵~）。
 */

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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

/** 本地占位预设卡（plan-standard-mode §5.7②）：真实数据（presets/list）优先，同 id 出现即不渲染占位。
 *  meow-cordis（创造）已随 0.2.6 播种为真实预设（assets/dsh-presets/meow-cordis/），占位卡删除。 */
private val PLACEHOLDER_PRESETS = listOf(
    PresetEntry(
        id = "meow-code",
        name = "PTC",
        description = "程序化转化（PTC）预设：模型生成可执行代码步骤完成任务。" +
            "预设文件与闭包包未随本版启用。",
    ),
    PresetEntry(
        id = "meow-minimal",
        name = "极简",
        description = "极简预设：仅保留最小工具集，专注纯对话。" +
            "预设文件与闭包包未随本版启用。",
    ),
)

/** 右侧看板「工作设置」面板入口（ChatScreen 的 DashboardDrawer.workspaceSettingsPanel 槽位接线用） */
@Composable
fun WorkspaceSettingsPanel(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize()) {
        WorkspaceSection(vm = vm, modifier = Modifier.weight(1f))
        HorizontalDivider()
        AgentPresetSection(vm = vm, modifier = Modifier.weight(1f))
        HorizontalDivider()
        MemoryRoleSection(modifier = Modifier.weight(1f))
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

/** ② Agent 预设：动态结果 + 本地占位卡合并渲染，卡片说明可折叠，trust=user 长按删除 */
@Composable
private fun AgentPresetSection(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val presetCatalog by vm.presetCatalog.collectAsState()
    val defaultPreset by vm.defaultPreset.collectAsState()

    // 进面板刷新一次 presets/list（DSH 侧无推送事件，触发时机归 UI 层，喵~）
    LaunchedEffect(Unit) { vm.refreshPresets() }

    // 真实数据优先：presets/list 里已有的 id 不再渲染占位卡（未来播种了即自动替换）
    val realIds = presetCatalog.map { it.id }.toSet()
    val entries = presetCatalog + PLACEHOLDER_PRESETS.filter { it.id !in realIds }
    val placeholderIds = PLACEHOLDER_PRESETS.map { it.id }.toSet()

    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deletingPreset by remember { mutableStateOf<PresetEntry?>(null) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        AppSectionHeader("Agent 预设")
        Text(
            text = "预设决定新会话的能力组合，仅对新会话生效喵",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
        )
        entries.forEach { entry ->
            val isPlaceholder = entry.id in placeholderIds && realIds.none { it == entry.id }
            val disabled = isPlaceholder || entry.broken != null
            val isDefault = entry.id == defaultPreset && !disabled
            PresetCard(
                entry = entry,
                isDefault = isDefault,
                disabled = disabled,
                isPlaceholder = isPlaceholder,
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
 * - 卡片头：名称（name 缺省回退 id）+ 右侧当前默认高亮 ✓（可选卡行尾为「设为默认」动作）；
 * - 说明可折叠：默认一行 ellipsis，点卡片展开完整说明；
 * - broken 预设灰显 + 展开后显示原因；占位卡灰显标注「未启用」不可选；
 * - trust == "user" 的自定义预设长按删除（确认框）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(
    entry: PresetEntry,
    isDefault: Boolean,
    disabled: Boolean,
    isPlaceholder: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val containerColor = when {
        isDefault -> MaterialTheme.colorScheme.primaryContainer
        disabled -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onToggleExpand,
                // 仅 trust=user 的真实自定义预设支持长按删除（占位/内置不可删，喵~）
                onLongClick = if (!isPlaceholder && !disabled && entry.trust == "user") onRequestDelete else null,
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
                        text = if (isPlaceholder) "未启用" else "不可用",
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
            // 行尾注明（真实可选预设）：只对新会话生效
            if (!disabled && !isDefault) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "对新会话生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ─────────────────────────── ③ 记忆/角色栏 ───────────────────────────

/**
 * ③ 记忆/角色：本版只做角色展示（喵喵老师，唯一，不可切换）。
 *
 * 结构预留：后续记忆工具 + 角色目录落地后，这里变成多角色单选——数据源按目录扫描动态渲染
 * （与 Agent 预设的 presets/list 自动扫描同构：扫到什么渲染什么，App 不硬编码列表，喵~）。
 * 届时把 [RoleCard] 换成「角色列表 + 点击切换默认角色」即可，徽标「当前角色」语义不变。
 */
@Composable
private fun MemoryRoleSection(modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        AppSectionHeader("记忆 / 角色")

        // 当前角色（唯一；占位结构，数据源接口留空待角色目录落地）
        RoleCard(
            name = "喵喵老师",
            description = "陪你学习的猫娘老师，耐心又温柔喵~",
            isCurrent = true,
        )

        // 记忆功能占位（延后三期：memory 文件与注入开关本版不做，plan-standard-mode §2.7）
        Spacer(Modifier.height(10.dp))
        Text(
            text = "记忆功能即将上线，敬请期待喵~",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 角色卡片：头像 + 名称 + 一句话说明 + 「当前角色」徽标（唯一角色不可切换） */
@Composable
private fun RoleCard(
    name: String,
    description: String,
    isCurrent: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像位（圆形容器 + 图标；后续角色目录落地可换成真实头像文件）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "当前角色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
