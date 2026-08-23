package com.meow.academy.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.chat.ChatDatabase
import com.meow.academy.data.model.DEEPSEEK_PROVIDER
import com.meow.academy.data.model.ProviderProfile
import com.meow.academy.data.model.buildProviderDirectory
import com.meow.academy.data.model.parseCatalogProfiles
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.chat.SessionUsageStats
import com.meow.academy.rpc.DshChunkTypes
import com.meow.academy.rpc.DshConnectionState
import com.meow.academy.rpc.DshEventTypes
import com.meow.academy.rpc.DshParams
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.DshRpcClient
import com.meow.academy.rpc.DshTurnEndKinds
import com.meow.academy.rpc.LlmModelInfo
import com.meow.academy.rpc.LlmProviderInfo
import com.meow.academy.rpc.str
import com.meow.academy.runtime.RuntimeState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 新会话默认占位标题；用户在会话里发出第一条消息后自动替换为真实标题 */
private const val DEFAULT_SESSION_TITLE = "新会话"

/** 从用户消息提取会话标题：取第一行的第一个完整句子（。！？!?；; 结束），没有标点就截断第一行。 */
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
    private val settingsRepository = (app as MeowAcademyApp).settingsRepository
    private val modelCatalog = (app as MeowAcademyApp).modelCatalogRepository
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

    // ── 工具栏设置（模型 / 思考强度 / 网络搜索 / 聊天底图） ──
    val llmModel: StateFlow<String> = settingsRepository.llmModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "deepseek-v4-flash")
    val reasoningEffort: StateFlow<String> = settingsRepository.reasoningEffort
        .stateIn(viewModelScope, SharingStarted.Eagerly, "high")
    val webSearchEnabled: StateFlow<Boolean> = settingsRepository.webSearchEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val chatBackground: StateFlow<String> = settingsRepository.chatBackground
        .stateIn(viewModelScope, SharingStarted.Eagerly, "none")

    // ── 模型管理：可切换 provider 与模型列表（M4，从 DSH RPC 读取） ──
    private val DEFAULT_MODELS = listOf("deepseek-v4-flash", "deepseek-v4-pro")

    private val _providers = MutableStateFlow<List<LlmProviderInfo>>(emptyList())
    val providers: StateFlow<List<LlmProviderInfo>> = _providers.asStateFlow()

    private val _availableModels = MutableStateFlow(DEFAULT_MODELS)
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    /** modelId → 模型信息（含 inputModalities），发送图片时判断当前模型是否支持视觉 */
    private val _modelCatalog = MutableStateFlow<Map<String, LlmModelInfo>>(emptyMap())
    private val modelCatalogMap: Map<String, LlmModelInfo> get() = _modelCatalog.value

    /** 当前 provider（DataStore "deepseek" → DSH 路由 "deepseek-official"） */
    val currentProvider: StateFlow<String> = settingsRepository.llmProvider
        .map { if (it == "deepseek") "deepseek-official" else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "deepseek-official")

    private var client = runtimeManager.rpcClient

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

    // ── 当前会话调用量（右侧功能看板 · M6） ──
    private val _sessionUsageStats = MutableStateFlow<SessionUsageStats?>(null)
    val sessionUsageStats: StateFlow<SessionUsageStats?> = _sessionUsageStats.asStateFlow()

    init {
        // ① 前端解耦：立即用本地缓存渲染工具栏（无缓存则回退内置 DeepSeek），
        //    不等 DSH 后端加载完成——控件一进页面就是完整可用的
        viewModelScope.launch { applyCatalogCache() }
        // 模型管理里调整过的 provider 顺序实时同步到聊天工具栏
        viewModelScope.launch {
            settingsRepository.providerOrder.collect { order ->
                _providers.value = reorderLlmProviders(_providers.value, order)
            }
        }
        // 旧版本遗留的「新会话」：若已有历史消息，按第一条用户消息补齐标题
        viewModelScope.launch { autoTitleDefaultSessions() }
        // ② DSH 启动成功后：后台同步 provider 目录与当前 provider 的模型列表（并写缓存）+ 补发待发送队列
        //    flushPending 放独立协程，避免长时间补发阻塞 state collect
        viewModelScope.launch {
            runtimeManager.state.collect { s ->
                if (s is RuntimeState.Running) {
                    refreshModelCatalog()
                    refreshUsageStats()
                    launch { flushPending() }
                }
            }
        }
    }

    /**
     * 缓存优先渲染：从本地缓存构建工具栏（无缓存时至少显示内置 DeepSeek）。
     * provider 目录与设置页共用同一份（内置 DeepSeek + 预设 + 已配置 provider），
     * 优先用 providersJson 缓存（刷新时存的同一份共享目录），其次由 settingsDescribe 缓存构建；
     * 模型列表来自 settingsDescribe 缓存。
     */
    private suspend fun applyCatalogCache() {
        val cached = modelCatalog.catalogJson.first()
        val profiles = cached?.let {
            runCatching { parseCatalogProfiles(json.parseToJsonElement(it).jsonObject) }.getOrNull()
        }

        // ① provider 目录：providersJson 缓存（刷新时存的同一份共享目录）→ profiles 构建 → 内置 DeepSeek 兜底
        val order = settingsRepository.providerOrder.first()
        var providers: List<LlmProviderInfo>? = runCatching {
            modelCatalog.providersJson.first()?.let { raw ->
                json.parseToJsonElement(raw).jsonArray
                    .mapNotNull { el -> runCatching { json.decodeFromJsonElement(LlmProviderInfo.serializer(), el) }.getOrNull() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { reorderLlmProviders(it, order) }
        if (providers == null) {
            providers = buildProviderList(profiles ?: emptyMap(), settingsRepository.disabledProviders.first(), order)
        }
        // 内置 DeepSeek 兜底（双保险，保证列表里始终有官方入口）
        _providers.value = if (providers.any { it.provider == DEEPSEEK_PROVIDER }) providers
            else listOf(LlmProviderInfo(provider = DEEPSEEK_PROVIDER, displayName = "DeepSeek", registered = true)) + providers

        // ② 模型列表：settingsDescribe 缓存里当前 provider 的 models，缺则默认
        _availableModels.value = if (cached != null) buildModelList(profiles) else DEFAULT_MODELS
    }

    /** 由缓存 profiles 构建与设置页一致的 provider 目录（内置 DeepSeek 兜底 + 禁用过滤 + 用户顺序） */
    private fun buildProviderList(
        profiles: Map<String, ProviderProfile>,
        disabled: Set<String>,
        order: List<String>,
    ): List<LlmProviderInfo> =
        buildProviderDirectory(profiles, disabled, order).map { e ->
            LlmProviderInfo(provider = e.key, displayName = e.displayName, registered = e.registered)
        }

    /** 把本地缓存里的 provider 目录按用户自定义顺序重排（缺失 key 追加到尾部） */
    private fun reorderLlmProviders(
        providers: List<LlmProviderInfo>,
        order: List<String>,
    ): List<LlmProviderInfo> {
        if (order.isEmpty()) return providers
        val byKey = providers.associateBy { it.provider }
        val known = order.mapNotNull(byKey::get)
        return known + providers.filter { it.provider !in order }
    }

    /** 当前 provider 的模型列表（缓存缺模型时回退默认） */
    private fun buildModelList(profiles: Map<String, ProviderProfile>?): List<String> {
        val cur = currentProvider.value
        val models = profiles?.get(cur)?.models?.map { it.id }.orEmpty()
        return if (models.isEmpty()) DEFAULT_MODELS else models
    }

    /** 当前流式会话对应的 DSH sessionId（停止生成用） */
    private var streamingDshSessionId: String? = null

    fun openSession(id: Long) {
        _currentSessionId.value = id
        // 切换会话先清旧统计，避免新会话还没有 stats 时面板显示上一会话数字
        _sessionUsageStats.value = null
    }

    /** 返回会话列表 */
    fun closeSession() {
        _currentSessionId.value = null
        _sessionUsageStats.value = null
    }

    /**
     * 刷新当前会话调用量统计。
     * 无会话 → 置 null；DSH 未就绪 / RPC 失败 / stats 缺失 → 保留旧值（面板仍可点“刷新”重试）。
     */
    fun refreshUsageStats() {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value ?: run {
                _sessionUsageStats.value = null
                return@launch
            }
            val rpc = runtimeManager.rpcClient ?: client ?: return@launch
            val result = rpc.sessionStats(dshSessionIdOf(sessionId)) ?: return@launch
            SessionUsageStats.parse(result)?.let { _sessionUsageStats.value = it }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val id = dao.insertSession(SessionEntity(title = DEFAULT_SESSION_TITLE))
            _currentSessionId.value = id
            _sessionUsageStats.value = null
        }
    }

    /**
     * 自动会话标题：仅当会话仍叫「新会话」时，用会话内第一条用户消息（还没有则用当前这条）
     * 生成标题；用户手动重命名过的会话不会被覆盖。
     */
    private suspend fun autoTitleSession(sessionId: Long, currentText: String) {
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
    private suspend fun autoTitleDefaultSessions() {
        for (session in dao.getSessionsByTitle(DEFAULT_SESSION_TITLE)) {
            val first = dao.getFirstUserMessage(session.id)?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: continue
            val title = generateSessionTitle(first)
            if (title != DEFAULT_SESSION_TITLE) {
                dao.updateSessionTitle(session.id, title)
            }
        }
    }

    fun deleteSession(session: SessionEntity) {
        viewModelScope.launch {
            dao.deleteMessages(session.id)
            dao.deleteSession(session)
            if (_currentSessionId.value == session.id) _currentSessionId.value = null
        }
    }

    /** Room 长 id → DSH sessionId；null（未打开会话）→ 空串：setModel 只更新服务端全局默认 */
    private fun dshSessionIdOf(roomId: Long?): String = if (roomId == null) "" else "room-$roomId"

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

        viewModelScope.launch {
            val sessionId = _currentSessionId.value
                ?: dao.insertSession(SessionEntity(title = DEFAULT_SESSION_TITLE)).also { _currentSessionId.value = it }
            autoTitleSession(sessionId, displayText)
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
            client = rpc
            runStream(assistantId, sessionId, dshSessionIdOf(sessionId), rpc, text, attachments)
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
    private suspend fun flushPending() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            val rpc = runtimeManager.rpcClient ?: return
            if (_streaming.value != null) return // 正在生成，等这条结束再触发
            client = rpc
            while (pendingQueue.isNotEmpty()) {
                // 连接断开（进程退出/重启中）：保留队列，等下次 Running 触发
                if (rpc.state.value !is DshConnectionState.Running) return
                val msg = pendingQueue.removeFirst()
                _pendingCount.value = pendingQueue.size
                runStream(msg.assistantMessageId, msg.roomSessionId, dshSessionIdOf(msg.roomSessionId), rpc, msg.text, msg.attachments)
            }
        } finally {
            flushing.set(false)
        }
    }

    /** 停止生成（session/cancel） */
    fun stopGenerating() {
        val sessionId = streamingDshSessionId ?: return
        viewModelScope.launch {
            client?.cancelSession(sessionId)
        }
    }

    /** 切换模型：更新全局默认 + 当前会话立即生效（session/setModel） */
    fun selectModel(model: String) {
        viewModelScope.launch {
            settingsRepository.setLlmModel(model)
            (runtimeManager.rpcClient ?: client)?.setModel(dshSessionIdOf(_currentSessionId.value), model = model)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /**
     * setModel 响应里服务端钳制后的思考强度同步回本地全局默认（工具栏与新会话保持一致）。
     * 服务端对不支持思考的模型会「不传强度」（selection 无 reasoningEffort），
     * 本地用 'off' 表达该状态（UI 显示「思考·关」，且 initialize 带的 off 也会被服务端钳掉）。
     */
    private fun syncEffectiveEffort(result: JsonObject?) {
        val sel = result?.get("selection")?.jsonObject ?: return
        val effort = sel["reasoningEffort"]?.jsonPrimitive?.contentOrNull ?: "off"
        viewModelScope.launch { settingsRepository.setReasoningEffort(effort) }
    }

    /** 刷新 provider 目录 + 当前 provider 的模型列表（进入聊天页/运行时启动后调用） */
    fun refreshModelCatalog() {
        viewModelScope.launch {
            val c = runtimeManager.rpcClient ?: return@launch
            val disabled = settingsRepository.disabledProviders.first()
            val order = settingsRepository.providerOrder.first()
            // provider 目录与设置页共用同一份（内置 + 预设 + 已配置），
            // 不用 llm/providers 的完整 pi-ai 目录（原始路由 id 与设置页名称对不上）
            val desc = c.settingsDescribe("llm-pi-ai")
            val profiles = desc?.let { runCatching { parseCatalogProfiles(it) }.getOrNull() } ?: emptyMap()
            val providers = buildProviderList(profiles, disabled, order)
            _providers.value = providers
            // 同步写本地缓存：下次打开（或 DSH 未就绪时）UI 可直接渲染，无需等后端
            runCatching {
                modelCatalog.saveProviders(json.encodeToString(ListSerializer(LlmProviderInfo.serializer()), providers))
            }
            desc?.let { resp -> runCatching { modelCatalog.saveCatalog(resp.toString()) } }
            val p = currentProvider.value
            val models = c.llmModels(p) ?: emptyList()
            _availableModels.value = if (models.isEmpty()) DEFAULT_MODELS else models.map { it.id }
            _modelCatalog.value = models.associateBy { it.id }
        }
    }

    /** 切换 provider：更新默认 + 选第一个模型 + 当前会话立即生效 */
    fun selectProvider(provider: String) {
        viewModelScope.launch {
            val c = runtimeManager.rpcClient ?: client
            val models = c?.llmModels(provider) ?: emptyList()
            val first = if (models.isEmpty()) DEFAULT_MODELS.first() else models.first().id
            settingsRepository.setLlmProvider(provider)
            settingsRepository.setLlmModel(first)
            _availableModels.value = if (models.isEmpty()) DEFAULT_MODELS else models.map { it.id }
            c?.setModel(dshSessionIdOf(_currentSessionId.value), provider = provider, model = first)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /** 切换思考强度：更新全局默认 + 当前会话立即生效（session/setModel） */
    fun selectReasoningEffort(effort: String) {
        viewModelScope.launch {
            settingsRepository.setReasoningEffort(effort)
            (runtimeManager.rpcClient ?: client)?.setModel(dshSessionIdOf(_currentSessionId.value), reasoningEffort = effort)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /** 切换网络搜索：写设置 + 重启 DSH（SQLite 持久化后会话自动 resume） */
    fun toggleWebSearch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWebSearchEnabled(enabled)
            runtimeManager.restart()
        }
    }

    /** 重命名会话（抽屉） */
    fun renameSession(sessionId: Long, title: String) {
        val t = title.trim()
        if (t.isNotEmpty()) viewModelScope.launch { dao.updateSessionTitle(sessionId, t) }
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

                // ③ 主路径：构造 contentBlocks（图片走 attachImages + image 块），发 session/prompt 等待受理确认
                val blocks = buildContentBlocks(promptText, attachments, rpc)
                val accepted = if (blocks != null) {
                    rpc.prompt(dshSessionId, blocks, timeoutMs = 15_000)
                } else {
                    // 图片上传/attach 失败 → 回退 Markdown 文本方式（模型看不到图但不丢消息）
                    rpc.prompt(dshSessionId, buildMessageWithAttachments(promptText, attachments), timeoutMs = 15_000)
                }
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
        // 回合结束后的调用量已有新数据（流结束后即读持久化日志）
        refreshUsageStats()
        // 队列可能还有待发送（生成中用户又发了消息）→ 触发补发
        viewModelScope.launch { flushPending() }
    }

    /**
     * 构造发送给 DSH 的 contentBlocks：
     * - 非图片附件 → Markdown 链接拼入 text 块；
     * - 图片附件 → 读文件转 base64 → session/attachImages 拿 durable refs → image 块。
     *
     * @return null 表示图片准备/上传失败，调用方应回退为纯文本发送（模型看不到图但不丢消息）。
     */
    private suspend fun buildContentBlocks(
        promptText: String,
        attachments: List<PendingAttachment>,
        rpc: DshRpcClient,
    ): List<DshParams.ContentBlock>? {
        // 只把后端支持的图片（jpeg/png/webp/gif）走 attachImages；bmp/svg 等按普通附件 Markdown 发送
        val imageAttachments = attachments.filter { isImageUploadable(it.displayName) }
        // 没有图片：非图片附件转 Markdown 拼入文本块即可
        if (imageAttachments.isEmpty()) {
            val text = buildTextContentWithNonImages(promptText, attachments)
            return listOf(DshParams.ContentBlock(type = "text", text = text))
        }

        // 当前模型明确不支持图片 → 直接回退 Markdown（不发起无谓的 attachImages）
        val supportsImage = modelCatalogMap[llmModel.value]?.supportsImage ?: true
        if (!supportsImage) {
            Log.w("ChatViewModel", "model ${llmModel.value} does not support image input, fallback to markdown")
            return null
        }

        // 读 limits（失败也能继续：用默认无限制流程，后端仍会兜底校验）
        val limits = runCatching { ImageLimits.from(rpc.imageLimits()) }.getOrNull()

        // 批量读文件 → base64（超限自动压缩）
        val prepared = prepareImageUploads(imageAttachments, limits)
        val failed = prepared.filter { it.second.isFailure }
        if (failed.isNotEmpty()) {
            Log.w(
                "ChatViewModel",
                "image prepare failed: " + failed.joinToString { "${it.first.displayName}: ${it.second.exceptionOrNull()?.message}" },
            )
            return null
        }
        val uploads = prepared.map { it.second.getOrThrow() }
        if (limits != null && uploads.size > limits.maxImagesPerMessage) {
            Log.w("ChatViewModel", "too many images: ${uploads.size} > ${limits.maxImagesPerMessage}")
            return null
        }

        // 调后端 attachImages → durable refs
        val refs = rpc.attachImages(uploads)
        if (refs == null || refs.size != uploads.size) {
            Log.w("ChatViewModel", "attachImages failed or ref count mismatch")
            return null
        }

        val blocks = mutableListOf<DshParams.ContentBlock>()
        val text = buildTextContentWithNonImages(promptText, attachments)
        if (text.isNotBlank()) {
            blocks += DshParams.ContentBlock(type = "text", text = text)
        }
        refs.forEach { ref ->
            blocks += DshParams.ContentBlock(type = "image", attachment = ref)
        }
        return blocks
    }

}
