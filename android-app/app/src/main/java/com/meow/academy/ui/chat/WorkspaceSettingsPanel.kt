package com.meow.academy.ui.chat

/**
 * 右侧功能看板「工作设置」面板（plan-standard-mode §5.7 / plan-memory-execution §3.1）。
 *
 * 单页三栏纵向排列（每栏 weight(1f) + 分片内部 verticalScroll，内容少时自然留白）：
 * ① 工作区   —— 新会话默认工作区展示 + 添加/切换（FolderPickerDialog 单根 filesDir）+
 *               候选列表（filesDir 本身 + 一级子目录动态扫描）+ 当前默认工作区会话列表；
 * ② Agent 预设 —— presets/list 动态结果渲染，卡片说明可折叠，trust=user 支持长按删除；
 * ③ 角色设定 —— 角色/记忆两个开关 + 角色选择器入口（PersonaPickerDialog）。
 *
 * 本文件是薄壳 + 唯一适配器：三分片全部纯参数注入（零 ChatViewModel 直连，可独立预览/复用）——
 * vm 的 11 个 flow 在这里 collect、5 个派生值（locked / blankSessionOpen / currentPresetId /
 * boundPersonaId / workspaceSessions）在这里算好、进面板刷新在这里触发后分发给三栏。
 * ChatScreen 只见 WorkspaceSettingsPanel(vm) 一个词（DashboardDrawer.workspaceSettingsPanel 槽位）。
 *
 * 切换工作区 = 只写 DataStore（vm.switchWorkspace），不重启 DSH、不打扰生成中的会话
 * （工作区 > 会话，归属随首条消息定死，喵~）。
 *
 * ⚠️ 刷新时机：refreshPresets/refreshPersonas 原本在②③两栏各自的 LaunchedEffect(Unit) 里，
 * 收敛到薄壳后三段同挂 → 进入组合的时机等价；若未来某栏改条件挂载，刷新触发要跟着回搬该栏。
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/** 右侧看板「工作设置」面板入口（ChatScreen 的 DashboardDrawer.workspaceSettingsPanel 槽位接线用） */
@Composable
fun WorkspaceSettingsPanel(vm: ChatViewModel) {
    // ── collect（11 个 flow；currentSession/messages/streaming 为②③两栏共享）──
    val sessions by vm.sessions.collectAsState()
    val defaultWorkspacePath by vm.defaultWorkspacePath.collectAsState()
    val presetCatalog by vm.presetCatalog.collectAsState()
    val defaultPreset by vm.defaultPreset.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val personaCatalog by vm.personaCatalog.collectAsState()
    val defaultPersonaId by vm.defaultPersonaId.collectAsState()
    val personaEnabled by vm.personaEnabled.collectAsState()
    val memoryEnabled by vm.memoryEnabled.collectAsState()

    // ── 派生（②③两栏原本各算一遍的锁定判定，收敛到这里只算一次，喵~）──
    // 会话已有消息（或在生成）→ 预设/角色已锁定；空白会话首条消息前可自由切换
    val locked = currentSession != null && (messages.isNotEmpty() || streaming != null)
    val blankSessionOpen = currentSession != null && !locked
    // 当前会话（或当前空白新会话）实际归属：优先会话行，未开会话回退新会话默认
    val currentPresetId = currentSession?.presetId?.takeIf { it.isNotBlank() } ?: defaultPreset
    val boundPersonaId = currentSession?.personaId?.takeIf { it.isNotBlank() } ?: defaultPersonaId
    // 预过滤：只把本工作区的会话交给①（分片不再触碰 sessions 全集）
    val workspaceSessions = sessions.filter { it.workspacePath == defaultWorkspacePath }

    // ── 刷新触发：进面板各刷一次 presets/list 与 personas/list（DSH 侧无推送事件，时机归 UI 层）──
    LaunchedEffect(Unit) {
        vm.refreshPresets()
        vm.refreshPersonas()
    }

    Column(Modifier.fillMaxSize()) {
        WorkspaceSection(
            workspaceSessions = workspaceSessions,
            defaultWorkspacePath = defaultWorkspacePath,
            onSwitchWorkspace = vm::switchWorkspace,
            onOpenSession = vm::openSession,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        AgentPresetSection(
            entries = presetCatalog,
            defaultPresetId = defaultPreset,
            currentPresetId = currentPresetId,
            locked = locked,
            blankSessionOpen = blankSessionOpen,
            onSelect = vm::selectDefaultPreset,
            onDelete = vm::deletePreset,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        PersonaSettingsSection(
            personas = personaCatalog,
            defaultPersonaId = defaultPersonaId,
            boundPersonaId = boundPersonaId,
            personaEnabled = personaEnabled,
            memoryEnabled = memoryEnabled,
            locked = locked,
            onSetPersonaEnabled = vm::setPersonaEnabled,
            onSetMemoryEnabled = vm::setMemoryEnabled,
            onSelectPersona = vm::selectDefaultPersona,
            onCreatePersona = vm::createPersona,
            onDeletePersona = vm::deletePersona,
            onReorderPersonas = vm::reorderPersonas,
            modifier = Modifier.weight(1f),
        )
    }
}
