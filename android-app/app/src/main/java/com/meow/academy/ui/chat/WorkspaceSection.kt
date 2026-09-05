package com.meow.academy.ui.chat

/**
 * 工作设置面板 ① 工作区栏（plan-standard-mode §5.7）：
 * 默认工作区行 + 添加/切换按钮 + 候选列表（filesDir 本身 + 一级子目录动态扫描）+
 * 当前默认工作区会话列表（[WorkspaceSessionRow]）+ FolderPickerDialog 接线。
 *
 * 纯展示分片：零 vm 直连（纯参数注入，可独立预览/复用），状态与回调全部由薄壳
 * （WorkspaceSettingsPanel，vm 的唯一适配器）注入。
 *
 * 切换工作区 = 只写 DataStore（薄壳适配器落盘），不重启 DSH、
 * 不打扰生成中的会话（工作区 > 会话，归属随首条消息定死，喵~）。
 */

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.ui.components.AppSectionHeader
import com.meow.academy.ui.files.FolderPickerDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ① 工作区：默认工作区行 + 添加/切换 + 候选列表 + 当前默认工作区会话列表。
 *
 * 局部状态（下沉在本分片，不上浮）：showPicker（选择器开关）+ candidates（候选目录，
 * LaunchedEffect(showPicker) 重扫——对话框里新建的文件夹关闭后即刻出现）。
 */
@Composable
internal fun WorkspaceSection(
    /** 预过滤：workspacePath == defaultWorkspacePath 的会话（薄壳算好传入） */
    workspaceSessions: List<SessionEntity>,
    defaultWorkspacePath: String,
    onSwitchWorkspace: (String) -> Unit,
    /** 打开会话（会话 id 是 Room 的 Long 主键，对齐上层 openSession(id: Long) 签名） */
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val filesDirPath = context.filesDir.absolutePath

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
        onSwitchWorkspace(path)
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
                WorkspaceSessionRow(session = session, onOpen = { onOpenSession(session.id) })
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
internal fun WorkspaceSessionRow(session: SessionEntity, onOpen: () -> Unit) {
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
