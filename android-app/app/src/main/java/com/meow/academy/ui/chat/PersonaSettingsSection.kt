package com.meow.academy.ui.chat

/**
 * 工作设置面板 ③ 角色设定栏（plan-memory-execution §3.1）：
 * 两个会话级开关（[SwitchRow]）+ 当前角色行 + 角色选择器入口（PersonaPickerDialog 接线）。
 *
 * 纯展示分片：零 vm 直连（纯参数注入），状态与回调全部由薄壳（WorkspaceSettingsPanel，
 * vm 的唯一适配器）注入——锁定判定（locked）薄壳算好传入，本分片不再重复计算。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.data.model.PersonaEntry
import com.meow.academy.ui.components.AppSectionHeader

/**
 * ③ 角色设定（plan-memory-execution §3.1）：两个会话级开关 + 角色选择器入口。
 *
 * - [角色开关] ON → 注入 <soul>/<user>；[记忆开关] ON → 注入 <facts>/存储契约并挂 memory 工具；
 *   两者默认 ON，改动写 DataStore（新会话默认）并同步当前空白会话行；
 * - 角色开关 OFF → 「选择角色」按钮**灰掉不可点**（design §2.3：不弹窗、personaId 不绑定）；
 * - 当前会话已有消息（或在生成）→ 角色锁定（[locked]），选择器以只读模式打开（§3.4）。
 *
 * 局部状态（下沉在本分片，不上浮）：showPicker（选择器开关）。
 */
@Composable
internal fun PersonaSettingsSection(
    personas: List<PersonaEntry>,
    defaultPersonaId: String,
    /** 当前会话锁定的角色（薄壳算好）；未开会话时 = defaultPersonaId */
    boundPersonaId: String,
    personaEnabled: Boolean,
    memoryEnabled: Boolean,
    /** 会话已有消息（或在生成）→ 选择器只读 + 角色行副注锁定（§3.4；薄壳统一判定传入） */
    locked: Boolean,
    onSetPersonaEnabled: (Boolean) -> Unit,
    onSetMemoryEnabled: (Boolean) -> Unit,
    onSelectPersona: (String) -> Unit,
    onCreatePersona: (id: String, name: String, description: String) -> Unit,
    onDeletePersona: (String) -> Unit,
    onReorderPersonas: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    // 当前角色名（catalog 查名，查不到回显 id）
    val boundName = personas.firstOrNull { it.id == boundPersonaId }?.name ?: boundPersonaId

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
            onCheckedChange = onSetPersonaEnabled,
        )
        // 记忆开关
        SwitchRow(
            title = "记忆",
            subtitle = if (memoryEnabled) "注入 <facts> + 存储契约，并挂 memory 工具" else "不注入事实、无 memory 工具",
            checked = memoryEnabled,
            onCheckedChange = onSetMemoryEnabled,
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
                        // 原判定 currentSession != null && messages.isNotEmpty() 与薄壳 locked 同源，收敛合一
                        locked -> "当前会话已锁定该角色"
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
        PersonaPickerDialog(
            personas = personas,
            selectedId = boundPersonaId.takeIf { it.isNotBlank() },
            locked = locked,
            onDismiss = { showPicker = false },
            onSelect = { id ->
                onSelectPersona(id)
                showPicker = false
            },
            onCreate = onCreatePersona,
            onDelete = onDeletePersona,
            onReorder = onReorderPersonas,
        )
    }
}

/** 开关行（标题 + 副注 + 右侧 Switch），两开关各占一行（§3.1） */
@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
