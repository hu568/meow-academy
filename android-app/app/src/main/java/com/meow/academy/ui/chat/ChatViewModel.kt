package com.meow.academy.ui.chat

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.chat.ChatDatabase
import com.meow.academy.data.model.DEEPSEEK_PROVIDER
import com.meow.academy.data.model.PresetCatalogRepository
import com.meow.academy.data.model.PresetEntry
import com.meow.academy.data.model.ProviderProfile
import com.meow.academy.data.model.buildProviderDirectory
import com.meow.academy.data.model.parseCatalogProfiles
import com.meow.academy.data.settings.ChatBackground
import com.meow.academy.data.settings.resolveChatBackground
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.chat.SessionUsageStats
import com.meow.academy.rpc.DshChunkTypes
import com.meow.academy.rpc.DshConnectionState
import com.meow.academy.rpc.DshError
import com.meow.academy.rpc.DshEvent
import com.meow.academy.rpc.DshEventTypes
import com.meow.academy.rpc.DshParams
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.DshRpcClient
import com.meow.academy.rpc.DshTurnEndKinds
import com.meow.academy.rpc.LlmModelInfo
import com.meow.academy.rpc.LlmProviderInfo
import com.meow.academy.rpc.bool
import com.meow.academy.rpc.str
import com.meow.academy.runtime.RuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/** jsonrpc error.code：预设不存在（data.available 有可用列表；message 本身也含，plan-standard-mode §三.8） */
private const val ERR_PRESET_UNKNOWN = -32001

/** jsonrpc error.code：预设存在但组合挂载失败（data.detail = 逐行原因） */
private const val ERR_PRESET_MOUNT_FAILED = -32002

/**
 * 附加模式（会话级能力开关：plan-mode / goal，plan-standard-mode §5.3）。
 *
 * 三态机：[pending] = true 表示附加命令已发出、状态事件（plan/mode、goal/change）未回——
 * 胶囊显示「生效中」转圈；事件到达后 pending 置 false 转确认态。UI 以 `is AttachedMode.Plan` /
 * `is AttachedMode.Goal` 判型，以 [pending] 区分「生效中 vs 已确认」。
 */
sealed interface AttachedMode {
    /** true = 命令已发出、事件未确认（胶囊「生效中」）；false = 事件已确认 */
    val pending: Boolean

    /** 规划模式（/plan 开启） */
    data class Plan(override val pending: Boolean = false) : AttachedMode

    /**
     * 目标模式（/goal <objective> 设定）。
     * @param objective 目标全文（胶囊只显示前 8 字摘要）
     * @param phase 生命周期 phase（active/paused/blocked/complete，GoalSnapshot.phase；水合/事件回填）
     */
    data class Goal(
        val objective: String,
        val phase: String? = null,
        override val pending: Boolean = false,
    ) : AttachedMode
}

/** 悬浮栏 todo 条目视图（todo/write 事件 / session/query 水合的 {content, status}） */
data class TodoItemView(val content: String, val status: String)

/** 悬浮栏子代理运行条目（subagent.started / subagent.finished 通知折叠） */
data class SubagentRun(
    val parentSessionId: String,
    val childSessionId: String,
    val provider: String? = null,
    val status: String? = null,
    val stopReason: String? = null,
    /** 收尾摘要（lastAssistantMessage，仅进程内子代理有） */
    val lastMessage: String? = null,
)

/** 待回答的问答（session.question 通知 → 问答卡交互通道，plan-standard-mode §三.6） */
data class PendingQuestion(
    val requestId: String,
    val sessionId: String?,
    val questions: List<QuestionItem>,
)

/** 单个问题（AskUserQuestionItem 的解析视图；detail = plan 审阅时的计划 Markdown 全文） */
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<OptionItem> = emptyList(),
    val multiSelect: Boolean = false,
    /** intent.kind（'plan-review' 时问答卡走计划审阅样式） */
    val intentKind: String? = null,
    /** intent.approve（plan 审阅批准选项的原文 label，回答按它回传） */
    val intentApprove: String? = null,
)

/** 单个选项（AskUserQuestionOption：label 必填 + 可选一句话说明） */
data class OptionItem(val label: String, val description: String? = null)

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

    /**
     * Agent 预设目录缓存（非单例，按文件名共享同一个 DataStore，与看板「工作设置」读到同一份）。
     */
    private val presetCatalogRepo = PresetCatalogRepository(app)
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

    /** 当前打开会话的实体（快捷文件/抽屉等跟随会话工作区与预设用；未打开为 null） */
    val currentSession: StateFlow<SessionEntity?> = _currentSessionId.flatMapLatest { id ->
        if (id == null) flowOf(null) else dao.observeSession(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── 工作设置（plan-standard-mode §5.3：工作区 / Agent 预设 / 会话过滤） ──

    /** 会话抽屉显示过滤（"all" 全部 / "workspace" 当前工作区；转发 DataStore） */
    val sessionFilter: StateFlow<String> = settingsRepository.sessionFilter
        .stateIn(viewModelScope, SharingStarted.Eagerly, "all")

    /** 新会话默认 Agent 预设 id（默认 meow-standard；只对新会话生效） */
    val defaultPreset: StateFlow<String> = settingsRepository.defaultPreset
        .stateIn(viewModelScope, SharingStarted.Eagerly, "meow-standard")

    /** 新会话默认工作区绝对路径（默认 filesDir/workspace；切换只写 DataStore，不重启 DSH） */
    val defaultWorkspacePath: StateFlow<String> = settingsRepository.workspacePath
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            java.io.File(app.filesDir, RuntimeExtractor.WORKSPACE_DIR).absolutePath,
        )

    /** Agent 预设目录（presets/list 缓存；DSH 未就绪时先渲染缓存，refreshPresets 覆盖） */
    val presetCatalog: StateFlow<List<PresetEntry>> = presetCatalogRepo.presets
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 附加模式 / 悬浮栏 / 问答（plan-standard-mode §5.3） ──

    /** 附加模式当前状态（null = 无附加；Plan/Goal + pending 区分生效中与已确认） */
    private val _attachedMode = MutableStateFlow<AttachedMode?>(null)
    val attachedMode: StateFlow<AttachedMode?> = _attachedMode.asStateFlow()

    /** 悬浮栏 todo 清单（todo/write 全量快照 / session/query 水合；null = 无数据不显示） */
    private val _todoState = MutableStateFlow<List<TodoItemView>?>(null)
    val todoState: StateFlow<List<TodoItemView>?> = _todoState.asStateFlow()

    /** 悬浮栏子代理运行清单（subagent.started/finished 实时通知；不做持久化水合） */
    private val _subagentRuns = MutableStateFlow<List<SubagentRun>>(emptyList())
    val subagentRuns: StateFlow<List<SubagentRun>> = _subagentRuns.asStateFlow()

    /** 待回答问答（session.question 通知；非空时问答卡交互启用，set-if-absent 防重连双投递） */
    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()

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

    /**
     * 当前模型支持的思考档位（聊天页思考按钮动态渲染的数据源）：
     * llm/models 的 reasoning 元数据与 setModel 响应的 modelReasoning 都会刷新；
     * 无能力数据时 DeepSeek 官方回退经典三档，第三方模型回退空列表（按钮禁用）。
     */
    private val _supportedEfforts = MutableStateFlow(listOf("off", "high", "max"))
    val supportedEfforts: StateFlow<List<String>> = _supportedEfforts.asStateFlow()

    /** 聊天底图（统一双模式解析：简单模式 DataStore / 动态模式 JSONC，输出可直接渲染的模型） */
    val chatBackground: StateFlow<ChatBackground> = combine(
        settingsRepository.backgroundDynamicEnabled,
        settingsRepository.chatBackground,
        (app as MeowAcademyApp).themeConfigRepository.config,
    ) { dynamic, dsRaw, configRaw ->
        resolveChatBackground(dynamic, dsRaw, configRaw, RuntimeExtractor.appConfigDir(app))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatBackground.None)

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

    // ── 全局事件收集器（subagent.*/session.question/todo/plan/goal 不按流式回合开关） ──
    // 生命周期跟随 rpcClient 实例：重连后 events 是新 SharedFlow，必须重新订阅（client = rpc 模式同款）
    private var globalEventsJob: Job? = null
    private var globalClient: DshRpcClient? = null

    /** 已成功水合（session/query）的 Room 会话 id；null/不同 = 待水合，DSH 转 Running 时重试 */
    private var hydratedRoomId: Long? = null

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

    // ── 右侧功能看板当前功能页（M6：持久化到 DataStore，退出 App 后仍记住上次的模式） ──
    val dashboardFeature: StateFlow<DashboardFeature> = settingsRepository.dashboardFeature
        .map { raw -> runCatching { DashboardFeature.valueOf(raw) }.getOrDefault(DashboardFeature.MODELS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DashboardFeature.MODELS)

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
                    refreshPresets() // 预设目录同步（触发点②：DSH 转 Running，§5.3）
                    hydrateCurrentSession() // 未就绪时水合失败 → 转 Running 对当前会话自动重试一次
                    subscribeGlobalEvents() // 新 rpcClient 实例 → 重挂全局事件收集器
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
        // 会话切换：todo / 附加模式 / 子代理列表清零，等 session/query 水合回填（subagent 不水合，§3.7）
        _todoState.value = null
        _attachedMode.value = null
        _subagentRuns.value = emptyList()
        hydratedRoomId = null
        hydrateCurrentSession()
    }

    /** 返回会话列表 */
    fun closeSession() {
        _currentSessionId.value = null
        _sessionUsageStats.value = null
        _todoState.value = null
        _attachedMode.value = null
        _subagentRuns.value = emptyList()
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

    /**
     * 新建会话：preset/workplace 归属缓冲进 Room 行（plan-standard-mode §3.4），
     * 随首条消息/首条命令携带给定死归属。
     */
    fun newSession() {
        viewModelScope.launch {
            val id = dao.insertSession(
                SessionEntity(
                    title = DEFAULT_SESSION_TITLE,
                    presetId = settingsRepository.defaultPreset.first(),
                    workspacePath = settingsRepository.workspacePath.first(),
                )
            )
            _currentSessionId.value = id
            _sessionUsageStats.value = null
            resetSessionViewState()
        }
    }

    /** 新开/新建会话后的视图状态复位（todo / 附加模式 / 子代理清单） */
    private fun resetSessionViewState() {
        _todoState.value = null
        _attachedMode.value = null
        _subagentRuns.value = emptyList()
        hydratedRoomId = null
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
            if (_currentSessionId.value == session.id) {
                _currentSessionId.value = null
                // UI 侧「自动打开最近会话」会落位到最近剩余会话并触发重新水合
                resetSessionViewState()
            }
        }
    }

    /**
     * 批量删除会话（多选模式确认删除用）：
     * 1. 先批量清空这些会话的消息（外键/级联依赖：单条删除时手动清消息，批量也对应处理）
     * 2. 再批量删除会话行
     * 3. 若当前打开的会话在删除集里 → 关掉（让用户重新选或新建）
     */
    fun deleteSessions(sessions: List<SessionEntity>) {
        if (sessions.isEmpty()) return
        viewModelScope.launch {
            val ids = sessions.map { it.id }
            dao.deleteMessagesBySessionIds(ids)
            dao.deleteSessionsByIds(ids)
            if (_currentSessionId.value != null && _currentSessionId.value in ids) {
                _currentSessionId.value = null
                _sessionUsageStats.value = null
                resetSessionViewState()
            }
        }
    }

    /** Room 长 id → DSH sessionId；null（未打开会话）→ 空串：setModel 只更新服务端全局默认 */
    internal fun dshSessionIdOf(roomId: Long?): String = if (roomId == null) "" else "room-$roomId"

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
            // 无会话自动建：preset/workplace 归属同样缓冲进 Room 行（§3.4，首条消息定死归属）
            val sessionId = _currentSessionId.value ?: dao.insertSession(
                SessionEntity(
                    title = DEFAULT_SESSION_TITLE,
                    presetId = settingsRepository.defaultPreset.first(),
                    workspacePath = settingsRepository.workspacePath.first(),
                )
            ).also {
                _currentSessionId.value = it
                resetSessionViewState()
            }
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
            // 先按模型目录刷新档位能力（setModel 不带 effort 时响应无 modelReasoning）
            syncSupportedEfforts(_modelCatalog.value[model]?.reasoning?.efforts)
            (runtimeManager.rpcClient ?: client)?.setModel(dshSessionIdOf(_currentSessionId.value), model = model)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /**
     * setModel 响应里服务端钳制后的思考强度同步回本地全局默认（工具栏与新会话保持一致）。
     * 服务端对不支持思考的模型会「不传强度」（selection 无 reasoningEffort），
     * 本地用 'off' 表达该状态（UI 显示「思考·关」，且 initialize 带的 off 也会被服务端钳掉）。
     * 响应携带的 modelReasoning（模型支持档位）同步进 [supportedEfforts]。
     */
    private fun syncEffectiveEffort(result: JsonObject?) {
        val sel = result?.get("selection")?.jsonObject ?: return
        val effort = sel["reasoningEffort"]?.jsonPrimitive?.contentOrNull ?: "off"
        viewModelScope.launch { settingsRepository.setReasoningEffort(effort) }
        result["modelReasoning"]?.jsonObject?.get("efforts")?.jsonArray?.let { arr ->
            syncSupportedEfforts(arr.mapNotNull { it.jsonPrimitive.contentOrNull })
        }
    }

    /** 思考档位同步入口：null = 无能力数据（按 provider 兜底）；空列表 = 明确不支持思考（按钮禁用） */
    private fun syncSupportedEfforts(efforts: List<String>?) {
        _supportedEfforts.value = efforts
            ?: if (currentProvider.value == DEEPSEEK_PROVIDER) listOf("off", "high", "max") else emptyList()
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
            // 当前模型的思考档位能力（第三方模型无声明确认时回退空列表 = 按钮禁用）
            syncSupportedEfforts(models.firstOrNull { it.id == llmModel.value }?.reasoning?.efforts)
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
            syncSupportedEfforts(models.firstOrNull { it.id == first }?.reasoning?.efforts)
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

    /** 切换右侧功能看板功能页：持久化到 DataStore，退出 App 后仍记住 */
    fun selectDashboardFeature(feature: DashboardFeature) {
        viewModelScope.launch { settingsRepository.setDashboardFeature(feature.name) }
    }

    // ── 工作设置（plan-standard-mode §5.3：预设目录 / 默认预设 / 工作区 / 会话过滤） ──

    /**
     * 拉取 presets/list 并覆盖本地缓存（自动扫描接口，App 不硬编码列表）。
     * 触发时机：进工作设置页（看板调用）+ DSH 转 Running；DSH 未就绪 / 失败 → 静默保留缓存。
     */
    fun refreshPresets() {
        viewModelScope.launch { presetCatalogRepo.refresh(runtimeManager.rpcClient ?: client) }
    }

    /** 设为默认 Agent 预设（只对新会话生效；DataStore） */
    fun selectDefaultPreset(id: String) {
        viewModelScope.launch { settingsRepository.setDefaultPreset(id) }
    }

    /**
     * 删除自定义预设（presets/delete，仅 trust=user 服务端放行）。
     * 若删除的是当前默认预设 → 自动回退 meow-standard，避免新会话无预设可用。
     */
    fun deletePreset(id: String) {
        viewModelScope.launch {
            val rpc = runtimeManager.rpcClient ?: client
            val ok = rpc?.presetsDelete(id) == true
            if (!ok) {
                toast("预设「$id」删除失败喵（内置预设不可删除）")
                return@launch
            }
            refreshPresets()
            if (settingsRepository.defaultPreset.first() == id) {
                settingsRepository.setDefaultPreset("meow-standard")
                toast("已删除预设「$id」，默认回退 meow-standard 喵~")
            } else {
                toast("已删除预设「$id」喵~")
            }
        }
    }

    /** 切换新会话默认工作区：只写 DataStore，绝不重启 DSH（生成中的会话 cwd 已定死，不受影响） */
    fun switchWorkspace(path: String) {
        viewModelScope.launch { settingsRepository.setWorkspacePath(path) }
    }

    /** 切换会话抽屉显示过滤（"all" / "workspace"，DataStore） */
    fun setSessionFilter(mode: String) {
        viewModelScope.launch { settingsRepository.setSessionFilter(mode) }
    }

    // ── 附加模式（/plan、/goal 斜杠命令经 session/command，plan-standard-mode §5.3） ──

    /** 附加规划模式（/plan） */
    fun attachPlan() = runModeCommand("/plan", optimistic = AttachedMode.Plan(pending = true))

    /** 关闭规划模式（/plan off） */
    fun detachPlan() = runModeCommand("/plan off", optimistic = AttachedMode.Plan(pending = true))

    /** 附加目标模式（/goal <objective>；目标必填，会立即驱动一个模型回合） */
    fun attachGoal(objective: String) {
        val text = objective.trim()
        if (text.isEmpty()) {
            toast("目标不能为空喵~")
            return
        }
        runModeCommand("/goal $text", optimistic = AttachedMode.Goal(objective = text, pending = true))
    }

    /** 清除目标（/goal clear） */
    fun detachGoal() {
        val objective = (_attachedMode.value as? AttachedMode.Goal)?.objective.orEmpty()
        runModeCommand("/goal clear", optimistic = AttachedMode.Goal(objective = objective, pending = true))
    }

    /**
     * 执行附加模式斜杠命令（session/command，携带 Room 行的 presetId/cwd——会话未建时创建即归属正确）。
     *
     * 三态机：发出即入「生效中」（optimistic pending 态）→
     * - kind=success：保持生效中，状态确认以 plan/mode、goal/change 事件为准（命令成功 ≠ 已生效）；
     * - kind=error：回退原状态 + toast 原文；
     * - jsonrpc reject（null）：回退 + toast；COMMAND_UNKNOWN 按会话区分——
     *   Room 行 presetId == null（升级前旧会话）→「该会话不支持附加模式」，否则「命令不存在」。
     */
    private fun runModeCommand(line: String, optimistic: AttachedMode) {
        val roomId = _currentSessionId.value ?: run {
            toast("先新建一个会话再附加模式喵~")
            return
        }
        val rpc = runtimeManager.rpcClient ?: client ?: run {
            toast("DSH 未就绪，稍后再试喵~")
            return
        }
        val previous = _attachedMode.value
        _attachedMode.value = optimistic
        viewModelScope.launch {
            val session = dao.getSession(roomId)
            val result = rpc.sessionCommand(
                dshSessionIdOf(roomId),
                line,
                presetId = session?.presetId,
                cwd = session?.workspacePath,
            )
            if (_currentSessionId.value != roomId) return@launch // 会话已切走，别动新会话状态
            when {
                result == null -> {
                    _attachedMode.value = previous
                    toast(
                        if (session?.presetId == null) "该会话不支持附加模式喵（升级前的旧会话）"
                        else "附加命令不存在喵（$line）"
                    )
                }
                result.str("kind") == "error" -> {
                    _attachedMode.value = previous
                    toast(result.str("text")?.takeIf { it.isNotBlank() } ?: "附加命令执行失败喵（$line）")
                }
                // kind=success 仅受理：等事件确认（pending 保持）
            }
        }
    }

    /** plan/mode 事件 → 附加模式状态（事件为准；active=true 确认 Plan，false 撤销） */
    private fun applyPlanModeEvent(data: JsonObject?) {
        val current = _attachedMode.value
        if (data?.bool("active") == true) {
            if (current !is AttachedMode.Plan || current.pending) {
                _attachedMode.value = AttachedMode.Plan(pending = false)
            }
        } else if (current is AttachedMode.Plan) {
            _attachedMode.value = null
        }
    }

    /**
     * goal/change 事件 → 附加模式状态。
     * 事件 data = GoalChangeMeta：{kind, version, operation, goal: GoalSnapshot, …}；
     * operation=clear 为墓碑（清除），快照变更从 goal.goal 取 objective/phase。
     */
    private fun applyGoalChangeEvent(data: JsonObject?) {
        if (data?.str("operation") == "clear") {
            if (_attachedMode.value is AttachedMode.Goal) _attachedMode.value = null
            return
        }
        val snapshot = data?.get("goal") as? JsonObject ?: return
        val objective = snapshot.str("objective") ?: return
        // 单槽位：goal 生效即覆盖 Plan（plan/goal 在 DSH 可共存，UI 先单槽）
        _attachedMode.value = AttachedMode.Goal(
            objective = objective,
            phase = snapshot.str("phase"),
            pending = false,
        )
    }

    // ── 会话状态水合（session/query，resume 后胶囊/悬浮栏恢复，§3.7） ──

    /**
     * 对当前会话做一次 session/query 水合（attachedMode / todoState；subagent 不水合）。
     * 失败（DSH 未就绪 / 超时）不置 hydratedRoomId → 转 Running 时自动重试一次。
     */
    private fun hydrateCurrentSession() {
        val roomId = _currentSessionId.value ?: return
        if (hydratedRoomId == roomId) return
        val rpc = runtimeManager.rpcClient ?: client ?: return
        viewModelScope.launch {
            val result = rpc.sessionQuery(dshSessionIdOf(roomId)) ?: return@launch
            if (_currentSessionId.value != roomId) return@launch // 已切走，丢弃
            hydratedRoomId = roomId
            _todoState.value = parseTodoItems(result["todos"] as? JsonArray)
            val plan = result["plan"] as? JsonObject
            val goal = result["goal"] as? JsonObject
            _attachedMode.value = when {
                plan?.bool("active") == true -> AttachedMode.Plan(pending = false)
                goal != null -> {
                    val snapshot = goal["goal"] as? JsonObject
                    AttachedMode.Goal(
                        objective = snapshot?.str("objective").orEmpty(),
                        phase = snapshot?.str("phase"),
                        pending = false,
                    )
                }
                else -> null
            }
        }
    }

    /** 解析 todo 数组（[{content, status}]）→ 悬浮栏视图模型；null/缺字段 → null（不显示） */
    private fun parseTodoItems(arr: JsonArray?): List<TodoItemView>? {
        if (arr == null) return null
        return arr.mapNotNull { el ->
            (el as? JsonObject)?.let { obj ->
                val content = obj.str("content") ?: return@mapNotNull null
                TodoItemView(content = content, status = obj.str("status") ?: "pending")
            }
        }.takeIf { it.isNotEmpty() }
    }

    // ── 全局事件收集器（不按 sessionId 过滤 + 当前会话能力事件，重连重挂） ──

    /**
     * 重挂全局事件收集器：rpcClient 实例更换（重连/重启）后 events 是新 SharedFlow。
     * 用实例同一性判重，Running 状态反复触发也不会重复订阅。
     */
    private fun subscribeGlobalEvents() {
        val rpc = runtimeManager.rpcClient ?: return
        if (rpc === globalClient) return
        globalEventsJob?.cancel()
        globalClient = rpc
        globalEventsJob = viewModelScope.launch {
            rpc.events.collect { ev -> runCatching { handleGlobalEvent(ev) } }
        }
    }

    /**
     * 全局事件处理：
     * - session.question → 待回答问答（set-if-absent：同一 requestId 的重连双投递忽略）；
     * - subagent.started/finished → 悬浮栏子代理清单（按 parent+child 去重）；
     * - todo/write、plan/mode、goal/change（限当前会话）：与 runStream 内 per-session 收集器
     *   同款处理——空闲会话的 /plan、/goal 事件在流式收集器之外到达，只有这里能接住。
     */
    private fun handleGlobalEvent(ev: DshEvent) {
        when (ev.type) {
            DshNotifMethods.SESSION_QUESTION -> {
                val requestId = ev.params.str("requestId") ?: return
                if (_pendingQuestion.value?.requestId == requestId) return // 重连双投递
                val questions = (ev.params["questions"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { el -> (el as? JsonObject)?.let(::parseQuestionItem) }
                _pendingQuestion.value = PendingQuestion(
                    requestId = requestId,
                    sessionId = ev.params.str("sessionId"),
                    questions = questions,
                )
            }
            "subagent.started" -> {
                val parent = ev.parentSessionId ?: return
                val child = ev.childSessionId ?: return
                if (_subagentRuns.value.any { it.parentSessionId == parent && it.childSessionId == child }) return
                _subagentRuns.value = _subagentRuns.value + SubagentRun(parentSessionId = parent, childSessionId = child)
            }
            "subagent.finished" -> {
                val parent = ev.parentSessionId ?: return
                val child = ev.childSessionId ?: return
                _subagentRuns.value = _subagentRuns.value.map { run ->
                    if (run.parentSessionId == parent && run.childSessionId == child) {
                        run.copy(
                            provider = ev.params.str("provider") ?: run.provider,
                            status = ev.params.str("status") ?: run.status,
                            stopReason = ev.params.str("stopReason") ?: run.stopReason,
                            lastMessage = ev.params.str("lastAssistantMessage") ?: run.lastMessage,
                        )
                    } else {
                        run
                    }
                }
            }
            DshEventTypes.TODO_WRITE -> {
                if (ev.sessionId == dshSessionIdOf(_currentSessionId.value)) {
                    _todoState.value = parseTodoItems(ev.data?.get("todos") as? JsonArray)
                }
            }
            DshEventTypes.PLAN_MODE -> {
                if (ev.sessionId == dshSessionIdOf(_currentSessionId.value)) applyPlanModeEvent(ev.data)
            }
            DshEventTypes.GOAL_CHANGE -> {
                if (ev.sessionId == dshSessionIdOf(_currentSessionId.value)) applyGoalChangeEvent(ev.data)
            }
        }
    }

    /** session.question 的单条问题解析（AskUserQuestionItem 视图） */
    private fun parseQuestionItem(obj: JsonObject): QuestionItem? {
        val id = obj.str("id") ?: return null
        val intent = obj["intent"] as? JsonObject
        val options = (obj["options"] as? JsonArray)?.mapNotNull { o ->
            (o as? JsonObject)?.let { OptionItem(label = it.str("label") ?: "", description = it.str("description")) }
        }.orEmpty()
        return QuestionItem(
            id = id,
            question = obj.str("question") ?: "",
            detail = obj.str("detail"),
            header = obj.str("header"),
            options = options,
            multiSelect = obj.bool("multiSelect") == true,
            intentKind = intent?.str("kind"),
            intentApprove = intent?.str("approve"),
        )
    }

    // ── 问答动作（session/answerQuestion，§三.6） ──

    /** 提交回答：成功后清除待回答状态（问答卡转已答折叠态由 tool/result 驱动） */
    fun answerQuestion(requestId: String, answers: List<DshParams.QuestionAnswer>) {
        viewModelScope.launch {
            val rpc = runtimeManager.rpcClient ?: client ?: return@launch
            val ok = rpc.answerQuestion(requestId, answers)
            if (ok && _pendingQuestion.value?.requestId == requestId) {
                _pendingQuestion.value = null
            } else if (!ok) {
                toast("回答送达失败喵，请重试")
            }
        }
    }

    /** 先不打扰：取消当前问答（模型收到取消语义，plan 审阅转「用户想直接说话」） */
    fun cancelQuestion(requestId: String) {
        viewModelScope.launch {
            val rpc = runtimeManager.rpcClient ?: client ?: return@launch
            val ok = rpc.answerQuestion(requestId, cancelled = true)
            if (_pendingQuestion.value?.requestId == requestId) {
                _pendingQuestion.value = null
            }
            if (!ok) toast("取消失败喵，请重试")
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
                                    DshEventTypes.TODO_WRITE -> {
                                        // 悬浮栏 todo 态：全量快照、last-wins（{todos: [{content, status}]}）
                                        _todoState.value = parseTodoItems(ev.data?.get("todos") as? JsonArray)
                                    }
                                    DshEventTypes.PLAN_MODE -> applyPlanModeEvent(ev.data)
                                    DshEventTypes.GOAL_CHANGE -> applyGoalChangeEvent(ev.data)
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
                // 会话归属随行（Room 行缓冲，plan-standard-mode §3.4）：每条 prompt 都携带
                // presetId/cwd，服务端对非空白会话忽略，多传无害；首条消息定死归属。
                val sessionRow = dao.getSession(roomSessionId)
                val rowPresetId = sessionRow?.presetId
                val rowCwd = sessionRow?.workspacePath
                val response = if (blocks != null) {
                    rpc.prompt(dshSessionId, blocks, presetId = rowPresetId, cwd = rowCwd, timeoutMs = 15_000)
                } else {
                    // 图片上传/attach 失败 → 回退 Markdown 文本方式（模型看不到图但不丢消息）
                    rpc.prompt(
                        dshSessionId,
                        buildMessageWithAttachments(promptText, attachments),
                        presetId = rowPresetId,
                        cwd = rowCwd,
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

    /**
     * prompt 受理失败的错误透传文本（plan-standard-mode §5.9）。
     * PRESET_UNKNOWN 的 data.available = 可用预设列表；PRESET_MOUNT_FAILED 的 data.detail = 逐行原因
     * （meow-jsonrpc 的 MeowRpcError 结构化载荷），都拼进气泡让错误可读。
     */
    private fun describePromptError(error: DshError): String = buildString {
        append(error.message.ifBlank { "prompt 被拒绝喵（未知错误）" })
        val data = error.data
        if (error.code == ERR_PRESET_UNKNOWN) {
            val available = data?.get("available") as? JsonArray
            if (available != null && available.isNotEmpty()) {
                append("\n可用预设：")
                available.forEachIndexed { i, item ->
                    if (i > 0) append("、")
                    append(item.jsonPrimitive.contentOrNull ?: item.toString())
                }
            }
        }
        if (error.code == ERR_PRESET_MOUNT_FAILED) {
            val detail = data?.get("detail")?.jsonPrimitive?.contentOrNull
            if (!detail.isNullOrBlank()) append("\n挂载详情：\n").append(detail)
        }
        if (error.code == ERR_PRESET_UNKNOWN || error.code == ERR_PRESET_MOUNT_FAILED) {
            append("\n\n到右侧看板 → 工作设置 → Agent 预设 检查或切换默认喵~")
        }
    }

    /** ViewModel 层轻提示（命令失败/问答失败等；application context Toast） */
    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

}
