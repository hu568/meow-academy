package com.meow.academy.ui.chat

/**
 * 角色选择器弹窗（plan-memory-execution §3.2）。
 *
 * 组成：
 * - 角色列表（personas/list 数据源，显示 name + description + id）：点选即设为默认角色，
 *   并同步写回当前空白会话的 Room 行；`locked = true`（会话已有消息）时整表只读；
 * - 新建角色：直接落空白三件套（persona.yml + SOUL.md + USER.md），不经过技能对话
 *   （后续可让 AI 用 soul-md-generator 技能填充，或手动编辑）；
 * - 删除角色：确认对话框后删整个 personas/<id>/ 目录；
 * - 长按拖拽排序：松手调 personas/reorder 持久化（复用设置页成熟库封装 ReorderableLazyColumn）。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.meow.academy.data.model.PersonaEntry
import com.meow.academy.ui.components.AppSectionHeader
import com.meow.academy.ui.settings.ReorderableLazyColumn

/** 角色 id 合法字符：小写字母/数字/中横线/下划线（文件夹名，跨端安全） */
private val PERSONA_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonaPickerDialog(
    personas: List<PersonaEntry>,
    /** 当前选中角色 id（默认角色；或会话锁定的角色） */
    selectedId: String?,
    /** true = 会话已有消息，角色已锁定：列表只读、不可选不可删（§3.4） */
    locked: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (id: String, name: String, description: String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PersonaEntry?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 头部：标题 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("选择角色", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (locked) "当前会话已锁定角色，新会话可自由切换喵~"
                            else "选中的角色对新会话与当前空白会话生效喵",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalDivider()

                // 角色列表（长按拖拽排序；locked 时禁用拖拽）
                if (personas.isEmpty()) {
                    Text(
                        text = "还没有角色喵~ 点下方「新建角色」创建空白三件套，" +
                            "或让 AI 按 personas/skills/soul-md-generator 技能访谈生成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                } else {
                    ReorderableLazyColumn(
                        items = personas,
                        key = { it.id },
                        enabled = !locked,
                        onDragEnd = { finalList -> onReorder(finalList.map { it.id }) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(horizontal = 8.dp),
                    ) { entry, isDragging ->
                        PersonaRow(
                            entry = entry,
                            selected = entry.id == selectedId,
                            isDragging = isDragging,
                            locked = locked,
                            onClick = { if (!locked) onSelect(entry.id) },
                            onRequestDelete = { deleting = entry },
                        )
                    }
                }

                HorizontalDivider()
                // 底部动作：新建角色
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建角色")
                }
            }
        }
    }

    if (showCreate) {
        CreatePersonaDialog(
            existingIds = personas.map { it.id }.toSet(),
            onDismiss = { showCreate = false },
            onConfirm = { id, name, description ->
                showCreate = false
                onCreate(id, name, description)
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除角色") },
            text = {
                Text("确定删除角色「${entry.name}」（id: ${entry.id}）吗？" +
                    "将删除 personas/${entry.id}/ 整个目录（SOUL.md / USER.md 一并没了），此操作不可撤销喵。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry.id)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

/** 单行角色卡：头像位 + 名称（+默认徽标）+ 简介 + 选中 ✓ / 删除按钮 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonaRow(
    entry: PersonaEntry,
    selected: Boolean,
    isDragging: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = null)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = entry.description.ifBlank { entry.id },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "当前角色",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            // 锁定会话不给删除入口（删了也解不开本会话的角色，反而困惑）
            if (!locked) {
                IconButton(onClick = onRequestDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除角色",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 新建角色对话框：id（文件夹名）+ 展示名 + 一句话简介；id 非法/重复即时报错 */
@Composable
private fun CreatePersonaDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (id: String, name: String, description: String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // 已试过的 id 记下来：重复提交同名时错误提示不会消失（否则用户看不出自己没改成）。
    // 不这么记也行——新建成功后才刷新列表，但那会让对话框在失败时一起被关掉。
    val triedIds = remember { mutableStateListOf<String>() }
    // id 留空时按展示名兜底一个 ascii 占位 id（避免中文文件夹名）
    val resolvedId = id.trim().ifBlank { "persona-${System.currentTimeMillis() % 100000}" }
    val idError = when {
        !PERSONA_ID_REGEX.matches(resolvedId) -> "只允许小写字母/数字/中横线/下划线，1-32 位"
        resolvedId in existingIds || resolvedId in triedIds -> "该 id 已存在"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建角色") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "创建空白三件套（persona.yml + SOUL.md + USER.md），" +
                        "之后可手动编辑或让 AI 用 soul-md-generator 技能填充喵~",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("展示名 *") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    singleLine = true,
                    label = { Text("一句话简介") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    singleLine = true,
                    isError = idError != null,
                    label = { Text("角色 id（留空自动生成）") },
                    supportingText = {
                        if (idError != null) {
                            Text(idError, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                AppSectionHeader("创建后可在 .agents/personas/$resolvedId/ 编辑内容")
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && idError == null,
                onClick = {
                    triedIds.add(resolvedId)
                    onConfirm(resolvedId, name.trim(), description.trim())
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
