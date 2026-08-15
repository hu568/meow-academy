package com.meow.academy.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.chat.ChatDatabase
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.rpc.DshChunkTypes
import com.meow.academy.rpc.DshConnectionState
import com.meow.academy.rpc.DshEventTypes
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.DshRpcClient
import com.meow.academy.rpc.DshTurnEndKinds
import com.meow.academy.rpc.str
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 聊天页 ViewModel（DSH 版，替代 pi 事件流）。
 *
 * 职责：会话列表/详情（Room）、发送消息（session/prompt）、流式增量渲染
 * （session.event 的 assistant/chunk）、停止生成（session/cancel）、工具调用卡片、错误兜底。
 *
 * 会话映射：Room 长 id → DSH sessionId = "room-<id>"；DSH 侧 JSONL 持久化负责模型上下文，
 * 同 id 重连时 meow-jsonrpc 插件走 resume 恢复历史。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDatabase.get(app).chatDao()
    private val runtimeManager = (app as MeowAcademyApp).runtimeManager
    private val json = Json { ignoreUnknownKeys = true }

    // ── 会话列表 ──
    val sessions: StateFlow<List<SessionEntity>> = dao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 当前会话 ──
    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _currentSessionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 流式增量（不落库的实时部分） ──
    private val _streaming = MutableStateFlow<StreamingState?>(null)
    val streaming: StateFlow<StreamingState?> = _streaming.asStateFlow()

    val isGenerating: StateFlow<Boolean> = _streaming
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var client = runtimeManager.rpcClient

    /** 当前流式会话对应的 DSH sessionId（停止生成用） */
    private var streamingDshSessionId: String? = null

    /**
     * 流式消息的实时状态（全 val 不可变）。
     * StateFlow 用 equals 判等去重，同一实例原地改字段再回写不会触发 emit，
     * 必须 copy 出新对象（与 TerminalEntry 同理，见踩坑记录 #5）。
     */
    data class StreamingState(
        val messageId: Long,
        val content: String = "",
        val thinking: String = "",
        val toolCalls: List<ToolCallInfo> = emptyList(),
    )

    data class ToolCallInfo(
        val id: String,
        val name: String,
        val arguments: String = "",
        val result: String = "",
        val isError: Boolean = false,
    )

    fun openSession(id: Long) {
        _currentSessionId.value = id
    }

    /** 返回会话列表 */
    fun closeSession() {
        _currentSessionId.value = null
    }

    fun newSession() {
        viewModelScope.launch {
            val id = dao.insertSession(SessionEntity(title = "新会话"))
            _currentSessionId.value = id
        }
    }

    fun deleteSession(session: SessionEntity) {
        viewModelScope.launch {
            dao.deleteMessages(session.id)
            dao.deleteSession(session)
            if (_currentSessionId.value == session.id) _currentSessionId.value = null
        }
    }

    /** Room 长 id → DSH sessionId */
    private fun dshSessionIdOf(roomId: Long): String = "room-$roomId"

    /**
     * 发送消息：落库用户消息 → 建 assistant 流式消息 → 订阅事件流 → session/prompt → 收集事件流。
     *
     * 顺序关键：**先订阅再发送**。DSH 的 session/prompt 立即回响应（受理确认），
     * turn/start / 首条 assistant/chunk 紧随其后（可能早于 send() 返回），
     * 若 send 后才订阅会漏掉开场事件（SharedFlow replay=0）。
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_streaming.value != null) return // 正在生成时不重复发送

        viewModelScope.launch {
            val sessionId = _currentSessionId.value
                ?: dao.insertSession(SessionEntity(title = trimmed.take(20))).also { _currentSessionId.value = it }
            dao.touchSession(sessionId)

            dao.insertMessage(MessageEntity(sessionId = sessionId, role = MessageRole.USER, content = trimmed))

            val assistantId = dao.insertMessage(
                MessageEntity(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    status = MessageStatus.STREAMING,
                )
            )

            val rpc = runtimeManager.rpcClient
            if (rpc == null) {
                dao.updateMessageContent(assistantId, "⚠️ DSH 运行时未启动，请到「设置」检查。", MessageStatus.ERROR)
                return@launch
            }
            client = rpc
            runStream(assistantId, sessionId, dshSessionIdOf(sessionId), rpc, trimmed)
        }
    }

    /** 停止生成（session/cancel） */
    fun stopGenerating() {
        val sessionId = streamingDshSessionId ?: return
        viewModelScope.launch {
            client?.cancelSession(sessionId)
        }
    }

    /**
     * 收集事件流直到 turn/end（或该会话 idle 兜底），实时更新 [streaming] 并节流落库。
     *
     * 结构：**子协程先订阅事件流**（收开场事件不丢），主路径再发 prompt 等待受理；
     * 收集子协程用 takeWhile 谓词在 turn/end 到达的当下终止（并顺手记录 reason.kind），
     * 监听 rpc.state，连接断开（进程崩溃/停止）时取消收集，防止悬挂。
     */
    private suspend fun runStream(
        assistantId: Long,
        roomSessionId: Long,
        dshSessionId: String,
        rpc: DshRpcClient,
        promptText: String,
    ) {
        var state = StreamingState(messageId = assistantId)
        _streaming.value = state
        streamingDshSessionId = dshSessionId
        var lastPersist = 0L

        val persist: suspend (StreamingState, MessageStatus) -> Unit = { s, status ->
            dao.updateMessageContent(assistantId, s.content, status)
            dao.updateMessageThinking(assistantId, s.thinking)
            dao.updateMessageTools(assistantId, json.encodeToString(JsonArray.serializer(), toolsToJson(s.toolCalls)))
            dao.touchSession(roomSessionId)
        }

        var errorMsg: String? = null
        // turn/end 的 reason.kind（takeWhile 谓词里记录，见下）
        var endKind: String? = null
        try {
            coroutineScope {
                // ① 事件收集子协程：只收本会话；turn/end 或 idle 到达时 takeWhile 立即终止
                val collector = launch {
                    rpc.events
                        .filter { it.sessionId == dshSessionId }
                        .takeWhile { ev ->
                            val isTurnEnd = ev.type == DshEventTypes.TURN_END
                            val isIdle = ev.method == DshNotifMethods.SESSION_STATUS && ev.status == "idle"
                            if (isTurnEnd) endKind = ev.turnEndKind ?: DshTurnEndKinds.COMPLETED
                            !isTurnEnd && !isIdle
                        }
                        .collect { ev ->
                            // 单事件处理异常不中断整个收集（解析失败只丢该事件）
                            runCatching {
                                when (ev.type) {
                                    DshEventTypes.ASSISTANT_CHUNK -> {
                                        val chunk = ev.chunk ?: return@runCatching
                                        when (chunk.str("type")) {
                                            DshChunkTypes.REASONING_DELTA ->
                                                state = state.copy(thinking = state.thinking + (chunk.str("text") ?: ""))
                                            DshChunkTypes.TEXT_DELTA ->
                                                state = state.copy(content = state.content + (chunk.str("text") ?: ""))
                                        }
                                        _streaming.value = state
                                        val now = System.currentTimeMillis()
                                        if (now - lastPersist > 250) {
                                            persist(state, MessageStatus.STREAMING)
                                            lastPersist = now
                                        }
                                    }
                                    DshEventTypes.TOOL_CALL -> {
                                        val id = ev.toolCallId ?: "tool-" + System.currentTimeMillis()
                                        val name = ev.toolName ?: "unknown"
                                        val args = ev.toolArguments ?: ""
                                        state = state.copy(toolCalls = state.toolCalls + ToolCallInfo(id = id, name = name, arguments = args))
                                        _streaming.value = state
                                    }
                                    DshEventTypes.TOOL_RESULT -> {
                                        val id = ev.toolCallId
                                        if (id == null) return@runCatching
                                        state = state.copy(toolCalls = state.toolCalls.map { old ->
                                            if (old.id != id) return@map old
                                            old.copy(
                                                result = ev.toolResultText ?: old.result,
                                                isError = ev.toolResultIsError,
                                            )
                                        })
                                        _streaming.value = state
                                    }
                                }
                            }.onFailure { e ->
                                Log.w("ChatViewModel", "event handling failed: " + ev.type, e)
                            }
                        }
                }
                // ② 连接断开兜底：进程崩溃/被停止 → 取消收集，避免无限悬挂
                val watcher = launch {
                    rpc.state.collect { s ->
                        if (s is DshConnectionState.Closed) {
                            Log.w("ChatViewModel", "rpc closed during stream, cancel collector")
                            collector.cancel()
                        }
                    }
                }

                // ③ 主路径：发 session/prompt 等待受理确认（事件由收集子协程处理）
                val accepted = rpc.prompt(dshSessionId, promptText, timeoutMs = 15_000)
                if (!accepted) {
                    errorMsg = "prompt 被拒绝（运行时异常或断连）"
                    collector.cancel() // 受理失败时显式取消收集，否则 coroutineScope 会永久等待
                } else {
                    // 等收集子协程自然结束（turn/end 或 idle 触发 takeWhile 终止）或被连接断开取消
                    collector.join()
                }
                watcher.cancel()
            }
        } catch (e: Exception) {
            Log.w("ChatViewModel", "stream failed", e)
            errorMsg = errorMsg ?: e.message
        }
        streamingDshSessionId = null

        // turn/end 的结束原因 → 最终状态
        when {
            errorMsg != null -> {
                dao.updateMessageContent(assistantId, "⚠️ " + errorMsg, MessageStatus.ERROR)
            }
            endKind == DshTurnEndKinds.ERROR -> {
                dao.updateMessageContent(assistantId, "⚠️ 生成出错，请重试", MessageStatus.ERROR)
            }
            // completed / max-tokens / aborted（用户停止）/ idle 兜底 → 都按已完成落库
            else -> persist(state, MessageStatus.DONE)
        }
        _streaming.value = null
    }

    private fun toolsToJson(tools: List<ToolCallInfo>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("id", t.id)
                put("name", t.name)
                put("arguments", t.arguments)
                put("result", t.result)
                put("isError", t.isError)
            })
        }
    }
}
