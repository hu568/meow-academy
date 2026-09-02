package com.meow.academy.ui.chat

/**
 * 聊天页顶栏分片（plan-chatscreen-refactor §2.2）：
 * 两行标题（工作区短名 · Agent 预设小字 + 会话标题大字）+ 三按钮（抽屉/看板/新会话）
 * + 重命名对话框。原始输入显式化传入，contextLine 组装与纯函数在本文件 internal。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.meow.academy.data.model.PersonaEntry
import com.meow.academy.data.model.PresetEntry

/**
 * 聊天页顶栏：两行标题 + 三按钮 + 重命名对话框。
 * @param workspacePath 当前会话工作区路径（未开会话为 null，分片内部回退默认）
 * @param currentPresetId 当前会话 Agent 预设 id（未开会话为 null，分片内部回退默认预设）
 * @param onRename 重命名保存回调（薄壳转发 vm.renameSession）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    currentId: Long?,
    currentTitle: String,
    workspacePath: String?,
    defaultWorkspacePath: String,
    currentPresetId: String?,
    defaultPreset: String,
    presetCatalog: List<PresetEntry>,
    /** 当前会话锁定的角色显示名（plan-memory-execution §3.4：只读展示，空串 = 角色开关 OFF / 未绑定） */
    personaName: String,
    filesDirPath: String,
    onRename: (Long, String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenDashboard: () -> Unit,
    onNewSession: () -> Unit,
) {
    // 顶栏小字：工作区短名 · Agent 预设显示名（§5.10；数据源 = 当前会话，未打开回退全局默认）
    // 角色名追加在末尾（plan-memory-execution §3.4：会话页只读显示当前角色，不可切）
    val contextLine = remember(
        workspacePath, defaultWorkspacePath, currentPresetId, defaultPreset, presetCatalog, personaName, filesDirPath,
    ) {
        buildString {
            append(topbarWorkspaceShortName(workspacePath ?: defaultWorkspacePath, filesDirPath))
            append(" · ")
            append(presetDisplayName(currentPresetId ?: defaultPreset.takeIf { it.isNotBlank() }, presetCatalog))
            if (personaName.isNotBlank()) {
                append(" · ")
                append(personaName)
            }
        }
    }

    // 顶栏标题点击重命名（状态随迁本分片；未开会话时标题不可点，不会弹）
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // ⚠️ 跨会话残留：对话框开着时切会话（currentId 变），内部 remember 状态不会自动重置，
    // 显式关闭，防止把旧标题改名到新会话（喵~）
    LaunchedEffect(currentId) {
        showRenameDialog = false
    }

    TopAppBar(
        // 两行标题（§5.10）：上方小字 = 工作区短名 · Agent 预设名（不可点击），
        // 下方大字 = 会话标题（重命名点击绑在大字上不动）。
        // 坑：M3 1.3.0 在 TopAppBar 内部对内容区做 windowInsetsPadding +
        // heightIn(max = expandedHeight=64dp)，外层 modifier.height() 会把
        // 内容区压成「自己高度 − 状态栏 inset」，两行必被裁——高度全权交给
        // 默认 expandedHeight，两行（labelSmall + 标题行）在 64dp 内垂直居中。
        title = {
            Column {
                Text(
                    text = contextLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = currentTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (currentId != null) {
                        Modifier.clickable {
                            renameText = currentTitle
                            showRenameDialog = true
                        }
                    } else {
                        Modifier
                    },
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "会话管理")
            }
        },
        actions = {
            IconButton(onClick = onOpenDashboard) {
                Icon(Icons.Outlined.Dashboard, contentDescription = "功能看板")
            }
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "新会话")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
    )

    // 顶栏标题点击 → 重命名当前会话（未打开会话时标题不可点，不会弹）
    if (showRenameDialog && currentId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(currentId, renameText)
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
        )
    }
}

/** Agent 预设显示名（PresetCatalog 缓存查 name，查不到显示 id，null → 「默认」；§5.10） */
internal fun presetDisplayName(presetId: String?, catalog: List<PresetEntry>): String {
    if (presetId.isNullOrBlank()) return "默认"
    return catalog.firstOrNull { it.id == presetId }?.name ?: presetId
}

/**
 * 角色显示名（plan-memory-execution §3.3/§3.4）：personaCatalog 缓存查 name。
 * - personaId 空 / 角色开关 OFF → 空串（不显示，调用方据此跳过分隔符）；
 * - 目录里查不到（角色文件夹被删）→「（已删除角色）」——注入内容仍按快照继续（快照价值所在）。
 */
internal fun personaDisplayName(personaId: String?, catalog: List<PersonaEntry>): String {
    if (personaId.isNullOrBlank()) return ""
    return catalog.firstOrNull { it.id == personaId }?.name?.takeIf { it.isNotBlank() }
        ?: "（已删除角色）"
}

/**
 * 顶栏小字用的工作区短名（§5.10，规则同 §5.7①）：
 * `<filesDir>/workspace` → 「workspace」、`<filesDir>` 本身 → 「files」、其余取最后一段文件夹名；
 * null（旧数据未写工作区）→ 按历史唯一工作区回退「workspace」。本文件私有实现，不与看板共用避免耦合。
 */
internal fun topbarWorkspaceShortName(path: String?, filesDirPath: String): String {
    val normalized = (path ?: "").trimEnd('/')
    val root = filesDirPath.trimEnd('/')
    return when {
        normalized.isEmpty() -> "workspace"
        normalized == root -> "files"
        normalized == "$root/workspace" -> "workspace"
        else -> normalized.substringAfterLast('/')
    }
}
