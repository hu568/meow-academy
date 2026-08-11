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
import com.meow.academy.rpc.PiConnectionState
import com.meow.academy.rpc.PiEventTypes
import com.meow.academy.rpc.RpcCommand
import com.meow.academy.rpc.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 聊天页 ViewModel（M2.4）。
 *
 * 职责：会话列表/详情（Room）、发送消息（RPC prompt）、流式增量渲染、
 * 停止生成（abort）、工具调用卡片、错误兜底。
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

    /**
     * 发送消息：落库用户消息 → 建 assistant 流式消息 → 订阅事件流 → RPC prompt → 收集事件流。
     *
     * 顺序关键：**先订阅再发送**。pi 的 prompt 立即回 `response`（受理确认），
     * `agent_start` / 首条 `message_update` 紧随其后（可能早于 send() 返回），
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
                dao.updateMessageContent(assistantId, "⚠️ Pi 运行时未启动，请到「设置」检查。", MessageStatus.ERROR)
                return@launch
            }
            client = rpc
            runStream(assistantId, sessionId, rpc, trimmed)
        }
    }

    /** 停止生成（abort） */
    fun stopGenerating() {
        viewModelScope.launch {
            client?.sendFireAndForget(RpcCommand(type = "abort"))
        }
    }

    /**
     * 收集事件流直到 agent_end，实时更新 [streaming] 并节流落库。
     *
     * 结构：**子协程先订阅事件流**（收开场事件不丢），主路径再发 prompt 等待受理；
     * 收集子协程用 `takeWhile { it.type != AGENT_END }` 在收到 agent_end 的当下立即终止
     * （若用 `takeWhile { !done }`，谓词在元素进 lambda 前求值，需再等一个事件才退出，
     * agent_end 后若无后续事件会永久挂起）；
     * 监听 rpc.state，连接断开（进程崩溃/停止）时取消收集，防止悬挂。
     */
    private suspend fun runStream(
        assistantId: Long,
        sessionId: Long,
        rpc: com.meow.academy.rpc.PiRpcClient,
        promptText: String,
    ) {
        var state = StreamingState(messageId = assistantId)
        _streaming.value = state
        var lastPersist = 0L

        val persist: suspend (StreamingState, MessageStatus) -> Unit = { s, status ->
            dao.updateMessageContent(assistantId, s.content, status)
            dao.updateMessageThinking(assistantId, s.thinking)
            dao.updateMessageTools(assistantId, json.encodeToString(JsonArray.serializer(), toolsToJson(s.toolCalls)))
            dao.touchSession(sessionId)
        }

        var errorMsg: String? = null
        try {
            coroutineScope {
                // ① 事件收集子协程（先订阅再发送，不丢开场事件）；
                //    agent_end 到达时 takeWhile 立即终止，无需等待下一个事件
                val collector = launch {
                    rpc.events.takeWhile { it.type != PiEventTypes.AGENT_END }.collect { ev ->
                        // 单事件处理异常不中断整个收集（Bug2 修复：partialResult/result 解析失败只丢该事件）
                        runCatching {
                            when (ev.type) {
                                PiEventTypes.MESSAGE_UPDATE -> {
                                    val msgEvent = ev.assistantMessageEvent ?: return@runCatching
                                    when (msgEvent.str("type")) {
                                        PiEventTypes.ASSISTANT_THINKING_DELTA ->
                                            state = state.copy(thinking = state.thinking + (msgEvent.str("delta") ?: ""))
                                        PiEventTypes.ASSISTANT_TEXT_DELTA ->
                                            state = state.copy(content = state.content + (msgEvent.str("delta") ?: ""))
                                    }
                                    _streaming.value = state
                                    val now = System.currentTimeMillis()
                                    if (now - lastPersist > 250) {
                                        persist(state, MessageStatus.STREAMING)
                                        lastPersist = now
                                    }
                                }
                                PiEventTypes.TOOL_EXECUTION_START -> {
                                    val id = ev.raw.str("toolCallId") ?: "tool-${System.currentTimeMillis()}"
                                    val name = ev.raw.str("toolName") ?: "unknown"
                                    val args = ev.raw["arguments"]?.toString() ?: ""
                                    state = state.copy(toolCalls = state.toolCalls + ToolCallInfo(id = id, name = name, arguments = args))
                                    _streaming.value = state
                                }
                                PiEventTypes.TOOL_EXECUTION_UPDATE -> {
                                    val id = ev.raw.str("toolCallId")
                                    state = state.copy(toolCalls = state.toolCalls.map {
                                        if (it.id == id) it.copy(result = ev.raw.str("partialResult") ?: it.result) else it
                                    })
                                    _streaming.value = state
                                }
                                PiEventTypes.TOOL_EXECUTION_END -> {
                                    val id = ev.raw.str("toolCallId")
                                    state = state.copy(toolCalls = state.toolCalls.map { old ->
                                        if (old.id != id) return@map old
                                        // result 结构：{"content":[{"type":"text","text":"..."},...],"details":{...}}
                                        val result = (ev.raw["result"] as? JsonObject)
                                            ?.let { r -> (r["content"] as? JsonArray)?.firstOrNull() as? JsonObject }
                                            ?.str("text")
                                            ?: ev.raw.str("result") // 兜底：字符串 result / 序列化展示
                                            ?: old.result
                                        val isError = ev.raw["isError"]?.let {
                                            (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBoolean()
                                        } ?: false
                                        old.copy(result = result, isError = isError)
                                    })
                                    _streaming.value = state
                                }
                            }
                        }.onFailure { e ->
                            Log.w("ChatViewModel", "event handling failed: ${ev.type}", e)
                        }
                    }
                }
                // ② 连接断开兜底：进程崩溃/被停止 → 取消收集，避免无限悬挂
                val watcher = launch {
                    rpc.state.collect { s ->
                        if (s is PiConnectionState.Closed) {
                            Log.w("ChatViewModel", "rpc closed during stream, cancel collector")
                            collector.cancel()
                        }
                    }
                }

                // ③ 主路径：发 prompt 等待受理确认（事件由收集子协程处理）
                val resp = rpc.send(RpcCommand(type = "prompt", message = promptText), timeoutMs = 15_000)
                if (resp == null || !resp.success) {
                    errorMsg = resp?.error ?: "prompt 被拒绝"
                    collector.cancel() // 受理失败时显式取消收集，否则 coroutineScope 会永久等待
                } else {
                    // 等收集子协程自然结束（agent_end 触发 takeWhile 终止）或被连接断开取消
                    collector.join()
                }
                watcher.cancel()
            }
        } catch (e: Exception) {
            Log.w("ChatViewModel", "stream failed", e)
            errorMsg = errorMsg ?: e.message
        }

        if (errorMsg != null) {
            dao.updateMessageContent(assistantId, "⚠️ $errorMsg", MessageStatus.ERROR)
            _streaming.value = null
            return
        }

        // 正常结束（agent_end 已收到）：最终内容落库为 DONE
        persist(state, MessageStatus.DONE)
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
