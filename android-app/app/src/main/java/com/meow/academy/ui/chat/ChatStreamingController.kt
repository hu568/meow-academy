package com.meow.academy.ui.chat

import android.util.Log
import com.meow.academy.data.chat.ChatDao
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.rpc.DshChunkTypes
import com.meow.academy.rpc.DshConnectionState
import com.meow.academy.rpc.DshEventTypes
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.DshRpcClient
import com.meow.academy.rpc.DshTurnEndKinds
import com.meow.academy.rpc.str
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * 流式核心控制器——发送/待发送队列/流式收集/停止（plan-chatviewmodel-refactor §2.1）。
 *
 * 状态所有权：streaming / isGenerating / pendingCount / pendingQueue / streamingDshSessionId。
 *
 * 回合边界在本控制器：runStream 内部 per-session 收集器（filter sessionId + takeWhile turn/end）
 * **保留**，但 when(ev.type) 只留流式三分支（ASSISTANT_CHUNK/TOOL_CALL/TOOL_RESULT）——
 * TODO/PLAN/GOAL 已移 ChatEventRouter → CapabilityController 全局单点，这里不再重复处理。
 */
class ChatStreamingController(
    private val scope: CoroutineScope,
    private val dao: ChatDao,
    private val runtimeManager: RuntimeManager,
    private val json: Json,
    private val sessionController: ChatSessionController,
    private val modelController: ChatModelController,
) {
    // ── 流式增量（不落库的实时部分） ──
    private val _streaming = MutableStateFlow<StreamingState?>(null)
    val streaming: StateFlow<StreamingState?> = _streaming.asStateFlow()

    val isGenerating: StateFlow<Boolean> = _streaming
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 待发送消息：DSH 未就绪/正在生成时入队，就绪后自动补发（sessionId 已落库，补发只走 DSH 侧） */
    private data class PendingMessage(
        val roomSessionId: Long,
        val assistantMessageId: Long,
        /** 原始用户输入（不含附件转成的 Markdown；展示文本已单独落库） */
        val text: String,
        val attachments: List<PendingAttachment> = emptyList(),
    )

    /** 待发送队列（进程内；App 被杀后由 cleanupStaleStreaming 兜底把占位标 ERROR） */
    private val pendingQueue = ArrayDeque<PendingMessage>()

    /** 防 flushPending 并发（多个触发点：Running 状态 / 每条生成结束） */
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 队列中待发送条数（UI 提示"待发送"用） */
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** 当前流式会话对应的 DSH sessionId（停止生成用） */
    private var streamingDshSessionId: String? = null

    // ── 发送 ──

    /**
     * 发送消息：落库用户消息 → 建 assistant 流式消息 → 订阅事件流 → session/prompt → 收集事件流。
     *
     * 附件处理：
     * - 图片附件 → 先读文件转 base64 → session/attachImages 拿 durable refs →
     *   session/prompt 的 contentBlocks 里放 image 块，让模型真正「看到」图片；
     * - 其他附件 → 转 Markdown 链接拼入文本块；
     * - 图片上传/attach 失败 → 回退为 Markdown 文本方式发送（模型看不到图，但不丢消息）。
     *
     * 前端解耦：DSH 未就绪或当前正在生成时**不报错、不丢弃**，而是入待发送队列，
     * DSH 就绪后（或当前条生成结束）由 [flushPending] 串行自动补发。
     *
     * 顺序关键：**先订阅再发送**。DSH 的 session/prompt 立即回响应（受理确认），
     * turn/start / 首条 assistant/chunk 紧随其后（可能早于 send() 返回），
     * 若 send 后才订阅会漏掉开场事件（SharedFlow replay=0）。
     */
    fun sendMessage(text: String, attachments: List<PendingAttachment> = emptyList()) {
        // 展示文本：图片和文件都转 Markdown（Room 落库、UI 渲染用）
        val displayText = buildMessageWithAttachments(text, attachments)
        if (displayText.isBlank()) return

        scope.launch {
            // 无会话自动建：preset/workplace 归属同样缓冲进 Room 行（§3.4，首条消息定死归属）
            val sessionId = sessionController.currentSessionId.value
                ?: sessionController.createSessionAndOpen()
            sessionController.autoTitleSession(sessionId, displayText)
            dao.touchSession(sessionId)

            dao.insertMessage(MessageEntity(sessionId = sessionId, role = MessageRole.USER, content = displayText))

            val assistantId = dao.insertMessage(
                MessageEntity(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    status = MessageStatus.STREAMING,
                )
            )

            val rpc = runtimeManager.rpcClient
            if (rpc == null || _streaming.value != null) {
                // 未就绪或正在生成 → 入队（不丢消息），就绪后自动补发
                enqueue(PendingMessage(roomSessionId = sessionId, assistantMessageId = assistantId, text = text, attachments = attachments))
                return@launch
            }
            runStream(assistantId, sessionId, sessionController.dshSessionIdOf(sessionId), rpc, text, attachments)
        }
    }

    /** 入队一条待发送消息：占位气泡给提示，避免 DSH 未就绪时静默排队 */
    private suspend fun enqueue(msg: PendingMessage) {
        dao.updateMessageContent(
            msg.assistantMessageId,
            "⏳ 等待 DSH 运行时就绪，将自动发送…",
            MessageStatus.STREAMING,
        )
        pendingQueue.addLast(msg)
        _pendingCount.value = pendingQueue.size
    }

    /**
     * 串行补发待发送队列（触发点：DSH 转 Running / 每条生成结束）。
     *
     * AtomicBoolean 防并发（多个触发点可能同时调用）；
     * 连接断开（进程退出/重启中）时保留队列，等下次 Running 再补发；
     * 正在生成（用户直发的消息在跑）时提前退出，生成结束会再次触发。
     */
    suspend fun flushPending() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            val rpc = runtimeManager.rpcClient ?: return
            if (_streaming.value != null) return // 正在生成，等这条结束再触发
            while (pendingQueue.isNotEmpty()) {
                // 连接断开（进程退出/重启中）：保留队列，等下次 Running 触发
                if (rpc.state.value !is DshConnectionState.Running) return
                val msg = pendingQueue.removeFirst()
                _pendingCount.value = pendingQueue.size
                runStream(msg.assistantMessageId, msg.roomSessionId, sessionController.dshSessionIdOf(msg.roomSessionId), rpc, msg.text, msg.attachments)
            }
        } finally {
            flushing.set(false)
        }
    }

    /** 停止生成（session/cancel） */
    fun stopGenerating() {
        val sessionId = streamingDshSessionId ?: return
        scope.launch {
            runtimeManager.rpcClient?.cancelSession(sessionId)
        }
    }

    // ── 流式核心（runStream） ──

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
        attachments: List<PendingAttachment> = emptyList(),
    ) {
        var state = StreamingState(messageId = assistantId)
        _streaming.value = state
        streamingDshSessionId = dshSessionId
        var lastPersist = 0L

        val persist: suspend (StreamingState, MessageStatus) -> Unit = { s, status ->
            dao.updateMessageContent(assistantId, "", status)
            dao.updateMessageSegments(assistantId, json.encodeToString(JsonArray.serializer(), segmentsToJson(s.segments)))
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
                                                state = state.copy(segments = appendReasoning(state.segments, chunk.str("text") ?: ""))
                                            DshChunkTypes.TEXT_DELTA ->
                                                state = state.copy(segments = appendText(state.segments, chunk.str("text") ?: ""))
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
                                        val call = ToolCallInfo(id = id, name = name, arguments = args)
                                        state = state.copy(segments = state.segments + Segment.Tool(call))
                                        _streaming.value = state
                                    }
                                    DshEventTypes.TOOL_RESULT -> {
                                        val id = ev.toolResultCallId
                                        if (id == null) return@runCatching
                                        state = state.copy(segments = state.segments.map { seg ->
                                            if (seg is Segment.Tool && seg.call.id == id) {
                                                seg.copy(call = seg.call.copy(
                                                    result = ev.toolResultText ?: seg.call.result,
                                                    isError = ev.toolResultIsError,
                                                ))
                                            } else {
                                                seg
                                            }
                                        })
                                        _streaming.value = state
                                    }
                                    // TODO_WRITE/PLAN_MODE/GOAL_CHANGE 已移到 ChatEventRouter 全局单点
                                }
                            }.onFailure { e ->
                                Log.w("ChatStreamingController", "event handling failed: " + ev.type, e)
                            }
                        }
                }
                // ② 连接断开兜底：进程崩溃/被停止 → 取消收集，避免无限悬挂
                val watcher = launch {
                    rpc.state.collect { s ->
                        if (s is DshConnectionState.Closed) {
                            Log.w("ChatStreamingController", "rpc closed during stream, cancel collector")
                            collector.cancel()
                        }
                    }
                }

                // ③ 主路径：构造 contentBlocks（图片走 attachImages + image 块），发 session/prompt 等待受理确认
                val blocks = buildContentBlocks(promptText, attachments, rpc, modelController)
                // 会话归属随行（Room 行缓冲，plan-standard-mode §3.4）：每条 prompt 都携带
                // presetId/cwd，服务端对非空白会话忽略，多传无害；首条消息定死归属。
                // personaId + 两开关同理随每条请求携带（plan-memory-execution §2.3）：
                // Room 行是唯一事实源，冷 resume 后 DSH 侧重建常驻 Map 全靠它。
                val sessionRow = dao.getSession(roomSessionId)
                val rowPresetId = sessionRow?.presetId
                val rowCwd = sessionRow?.workspacePath
                val rowPersonaId = sessionRow?.personaId
                val rowPersonaEnabled = sessionRow?.personaEnabled
                val rowMemoryEnabled = sessionRow?.memoryEnabled
                val response = if (blocks != null) {
                    rpc.prompt(
                        dshSessionId, blocks,
                        presetId = rowPresetId, cwd = rowCwd,
                        personaId = rowPersonaId,
                        personaEnabled = rowPersonaEnabled,
                        memoryEnabled = rowMemoryEnabled,
                        timeoutMs = 15_000,
                    )
                } else {
                    // 图片上传/attach 失败 → 回退 Markdown 文本方式（模型看不到图但不丢消息）
                    rpc.prompt(
                        dshSessionId,
                        buildMessageWithAttachments(promptText, attachments),
                        presetId = rowPresetId, cwd = rowCwd,
                        personaId = rowPersonaId,
                        personaEnabled = rowPersonaEnabled,
                        memoryEnabled = rowMemoryEnabled,
                        timeoutMs = 15_000,
                    )
                }
                // 受理失败 → 透传 error 载荷（§5.9：替换固定文案，错误原文进气泡）
                val promptError = response?.error
                errorMsg = when {
                    response == null -> "prompt 无响应（运行时未就绪或断连）喵…"
                    promptError != null -> describePromptError(promptError)
                    else -> null
                }
                if (errorMsg != null) {
                    collector.cancel() // 受理失败时显式取消收集，否则 coroutineScope 会永久等待
                } else {
                    // 等收集子协程自然结束（turn/end 或 idle 触发 takeWhile 终止）或被连接断开取消
                    collector.join()
                }
                watcher.cancel()
            }
        } catch (e: Exception) {
            Log.w("ChatStreamingController", "stream failed", e)
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
        // 回合结束后的调用量已有新数据（流结束后即读持久化日志）
        sessionController.refreshUsageStats()
        // 队列可能还有待发送（生成中用户又发了消息）→ 触发补发
        scope.launch { flushPending() }
    }
}