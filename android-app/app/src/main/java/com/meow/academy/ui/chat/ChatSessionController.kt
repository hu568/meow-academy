package com.meow.academy.ui.chat

import com.meow.academy.data.chat.ChatDao
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.chat.SessionUsageStats
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 会话状态控制器——会话列表/当前会话/CRUD/自动标题/调用量（plan-chatviewmodel-refactor §2.1）。
 *
 * 状态所有权：sessions / currentSessionId / messages / currentSession / sessionUsageStats。
 * 能力态清理（todo/attachedMode/subagent）**不归本类**：由 CapabilityController 自监听
 * currentSessionId（distinctUntilChanged + skip 初始）触发，第 3 步实现。
 */
class ChatSessionController(
    private val scope: CoroutineScope,
    private val dao: ChatDao,
    private val runtimeManager: RuntimeManager,
    private val settingsRepository: SettingsRepository,
    private val defaultWorkspaceAbsPath: String,
    /** filesDir 绝对路径（会话删除时清理 .agents/memory/snapshots 用，plan-memory-execution §2.4） */
    private val filesDirPath: String,
) {
    // ── 会话列表 ──
    val sessions: StateFlow<List<SessionEntity>> = dao.observeSessions()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ── 当前会话 ──
    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _currentSessionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 当前打开会话的实体（快捷文件/抽屉等跟随会话工作区与预设用；未打开为 null） */
    val currentSession: StateFlow<SessionEntity?> = _currentSessionId.flatMapLatest { id ->
        if (id == null) flowOf(null) else dao.observeSession(id)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    // ── 当前会话调用量（右侧功能看板 · M6） ──
    private val _sessionUsageStats = MutableStateFlow<SessionUsageStats?>(null)
    val sessionUsageStats: StateFlow<SessionUsageStats?> = _sessionUsageStats.asStateFlow()

    /** Room 长 id → DSH sessionId；null（未打开会话）→ 空串：setModel 只更新服务端全局默认 */
    internal fun dshSessionIdOf(roomId: Long?): String = if (roomId == null) "" else "room-$roomId"

    // ── 会话切换 ──

    fun openSession(id: Long) {
        _currentSessionId.value = id
        // 切换会话先清旧统计，避免新会话还没有 stats 时面板显示上一会话数字
        _sessionUsageStats.value = null
        // 能力态清理（todo/attachedMode/subagent）由 CapabilityController 自监听 currentSessionId 触发
    }

    /** 返回会话列表 */
    fun closeSession() {
        _currentSessionId.value = null
        _sessionUsageStats.value = null
    }

    // ── 调用量 ──

    /**
     * 刷新当前会话调用量统计。
     * 无会话 → 置 null；DSH 未就绪 / RPC 失败 / stats 缺失 → 保留旧值（面板仍可点"刷新"重试）。
     */
    fun refreshUsageStats() {
        scope.launch {
            val sessionId = _currentSessionId.value ?: run {
                _sessionUsageStats.value = null
                return@launch
            }
            val rpc = runtimeManager.rpcClient ?: return@launch
            val result = rpc.sessionStats(dshSessionIdOf(sessionId)) ?: return@launch
            SessionUsageStats.parse(result)?.let { _sessionUsageStats.value = it }
        }
    }

    // ── 会话 CRUD ──

    /**
     * 插入新会话并设为当前，返回新 id。
     * 供 [ChatViewModel.sendMessage] 自动建会话用（preset/workplace/角色绑定/两开关缓冲进 Room 行）。
     * @param personaId 显式指定角色 id；null = 从 DataStore 默认角色读取（角色开关 OFF 时为 null）。
     * @param personaEnabled / [memoryEnabled] 传 null = 从 DataStore 新会话默认读取。
     */
    suspend fun createSessionAndOpen(
        personaId: String? = null,
        personaEnabled: Boolean? = null,
        memoryEnabled: Boolean? = null,
    ): Long {
        val enabled = personaEnabled ?: settingsRepository.personaEnabled.first()
        val memory = memoryEnabled ?: settingsRepository.memoryEnabled.first()
        // 调用方没显式传角色时，从新会话默认读取（与 preset/workspace 同款 DataStore 模式）；
        // 角色开关 OFF 时不绑定 personaId。
        val resolvedPersonaId = if (personaId === null && enabled) {
            settingsRepository.defaultPersonaId.first().takeIf { it.isNotBlank() }
        } else {
            personaId
        }
        val id = dao.insertSession(
            SessionEntity(
                title = DEFAULT_SESSION_TITLE,
                presetId = settingsRepository.defaultPreset.first(),
                workspacePath = settingsRepository.workspacePath.first(),
                personaId = resolvedPersonaId,
                personaEnabled = enabled,
                memoryEnabled = memory,
            )
        )
        _currentSessionId.value = id
        _sessionUsageStats.value = null
        return id
    }

    /**
     * 新建会话：preset/workplace/角色 归属缓冲进 Room 行（plan-standard-mode §3.4，
     * plan-memory-execution §2.2），随首条消息/首条命令携带给定死归属。
     * @param personaId 显式指定角色（角色选择器「新建会话即用此角色」，§3.2）；null = 用默认。
     */
    fun newSession(personaId: String? = null) {
        scope.launch { createSessionAndOpen(personaId = personaId) }
    }

    fun deleteSession(session: SessionEntity) {
        scope.launch {
            dao.deleteMessages(session.id)
            dao.deleteSession(session)
            deleteSnapshotFile(session.id)
            if (_currentSessionId.value == session.id) {
                _currentSessionId.value = null
            }
        }
    }

    /**
     * 批量删除会话（多选模式确认删除用）。
     */
    fun deleteSessions(sessions: List<SessionEntity>) {
        if (sessions.isEmpty()) return
        scope.launch {
            val ids = sessions.map { it.id }
            dao.deleteMessagesBySessionIds(ids)
            dao.deleteSessionsByIds(ids)
            ids.forEach { deleteSnapshotFile(it) }
            if (_currentSessionId.value != null && _currentSessionId.value in ids) {
                _currentSessionId.value = null
                _sessionUsageStats.value = null
            }
        }
    }

    /**
     * 会话删除时顺带清理 DSH 侧快照文件（plan-memory-execution §2.4）：
     * `.agents/memory/snapshots/<sessionId>.json`（sessionId = "room-<id>"，与 DSH 侧一致）。
     * 删除失败静默（文件可能本就不存在 / DSH 未初始化）。
     */
    private fun deleteSnapshotFile(roomId: Long) {
        runCatching {
            File(
                File(filesDirPath, RuntimeExtractor.AGENTS_DIR),
                "memory/snapshots/${dshSessionIdOf(roomId)}.json",
            ).delete()
        }
    }

    /** 重命名会话（抽屉） */
    fun renameSession(sessionId: Long, title: String) {
        val t = title.trim()
        if (t.isNotEmpty()) scope.launch { dao.updateSessionTitle(sessionId, t) }
    }

    // ── 自动标题 ──

    /**
     * 自动会话标题：仅当会话仍叫「新会话」时，用会话内第一条用户消息（还没有则用当前这条）
     * 生成标题；用户手动重命名过的会话不会被覆盖。
     */
    suspend fun autoTitleSession(sessionId: Long, currentText: String) {
        val session = dao.getSession(sessionId) ?: return
        if (session.title != DEFAULT_SESSION_TITLE) return

        val firstUserMessage = dao.getFirstUserMessage(sessionId)?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
        val title = generateSessionTitle(firstUserMessage ?: currentText)
        if (title != DEFAULT_SESSION_TITLE) {
            dao.updateSessionTitle(sessionId, title)
        }
    }

    /** 启动时补齐旧数据：把已有消息但标题还是「新会话」的会话，用第一条用户消息生成标题 */
    fun autoTitleDefaultSessions() {
        scope.launch {
            for (session in dao.getSessionsByTitle(DEFAULT_SESSION_TITLE)) {
                val first = dao.getFirstUserMessage(session.id)?.content?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val title = generateSessionTitle(first)
                if (title != DEFAULT_SESSION_TITLE) {
                    dao.updateSessionTitle(session.id, title)
                }
            }
        }
    }

    companion object {
        /** 新会话默认占位标题；用户在会话里发出第一条消息后自动替换为真实标题 */
        internal const val DEFAULT_SESSION_TITLE = "新会话"

        /** 从用户消息提取会话标题：取第一行的第一个完整句子 */
        private fun generateSessionTitle(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return DEFAULT_SESSION_TITLE

            val firstLine = trimmed.lineSequence().first().trim()
            val sentence = buildString {
                for (ch in firstLine) {
                    append(ch)
                    if (ch in "。！？!?；;") break
                }
            }.trim()

            return (if (sentence.isNotEmpty()) sentence else firstLine).take(30)
        }
    }
}