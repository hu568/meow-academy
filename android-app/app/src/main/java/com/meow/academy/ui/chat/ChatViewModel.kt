package com.meow.academy.ui.chat

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.chat.ChatDatabase
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.SessionEntity
import com.meow.academy.data.chat.SessionUsageStats
import com.meow.academy.data.model.PresetCatalogRepository
import com.meow.academy.data.model.PresetEntry
import com.meow.academy.data.settings.ChatBackground
import com.meow.academy.rpc.DshParams
import com.meow.academy.rpc.LlmProviderInfo
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.runtime.RuntimeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 聊天页 ViewModel 薄门面（1308→~200 行，plan-chatviewmodel-refactor）。
 * 创建/持有 5 职责控制器 + 1 Router，组合暴露 StateFlow，转发 UI 动作。
 * UI 层零改动（vm.xxx 引用面原样保留）。
 * 会话映射：Room id → "room-<id>"，DSH 侧 JSONL 持久化，重启走 resume。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDatabase.get(app).chatDao()
    private val runtimeManager = (app as MeowAcademyApp).runtimeManager
    private val settingsRepository = (app as MeowAcademyApp).settingsRepository

    private val defaultWorkspaceAbsPath: String =
        File(app.filesDir, RuntimeExtractor.WORKSPACE_DIR).absolutePath

    // ── 职责控制器 ──

    private val sessionController = ChatSessionController(
        scope = viewModelScope, dao = dao, runtimeManager = runtimeManager,
        settingsRepository = settingsRepository, defaultWorkspaceAbsPath = defaultWorkspaceAbsPath,
    )
    private val modelController = ChatModelController(
        scope = viewModelScope, settingsRepository = settingsRepository,
        modelCatalog = (app as MeowAcademyApp).modelCatalogRepository,
        runtimeManager = runtimeManager,
        themeConfigRepository = (app as MeowAcademyApp).themeConfigRepository,
        appConfigDir = RuntimeExtractor.appConfigDir(app),
    )
    private val presetController = ChatPresetController(
        scope = viewModelScope, settingsRepository = settingsRepository,
        presetCatalogRepo = PresetCatalogRepository(app),
        runtimeManager = runtimeManager, defaultWorkspaceAbsPath = defaultWorkspaceAbsPath,
        toast = ::toast,
    )
    private val capabilityController = ChatCapabilityController(
        scope = viewModelScope, dao = dao, runtimeManager = runtimeManager,
        sessionController = sessionController, toast = ::toast,
    )
    private val streamingController = ChatStreamingController(
        scope = viewModelScope, dao = dao, runtimeManager = runtimeManager,
        json = Json { ignoreUnknownKeys = true },
        sessionController = sessionController, modelController = modelController,
    )
    private val eventRouter = ChatEventRouter(viewModelScope, capabilityController)

    // ── 组合暴露：会话 ──

    val sessions: StateFlow<List<SessionEntity>> = sessionController.sessions
    val currentSessionId: StateFlow<Long?> = sessionController.currentSessionId
    val messages: StateFlow<List<MessageEntity>> = sessionController.messages
    val currentSession: StateFlow<SessionEntity?> = sessionController.currentSession
    val sessionUsageStats: StateFlow<SessionUsageStats?> = sessionController.sessionUsageStats

    // ── 组合暴露：模型/工具栏 ──

    val llmModel: StateFlow<String> = modelController.llmModel
    val reasoningEffort: StateFlow<String> = modelController.reasoningEffort
    val webSearchEnabled: StateFlow<Boolean> = modelController.webSearchEnabled
    val supportedEfforts: StateFlow<List<String>> = modelController.supportedEfforts
    val chatBackground: StateFlow<ChatBackground> = modelController.chatBackground
    val providers: StateFlow<List<LlmProviderInfo>> = modelController.providers
    val availableModels: StateFlow<List<String>> = modelController.availableModels
    val currentProvider: StateFlow<String> = modelController.currentProvider

    // ── 组合暴露：工作设置 ──

    val sessionFilter: StateFlow<String> = presetController.sessionFilter
    val defaultPreset: StateFlow<String> = presetController.defaultPreset
    val defaultWorkspacePath: StateFlow<String> = presetController.defaultWorkspacePath
    val presetCatalog: StateFlow<List<PresetEntry>> = presetController.presetCatalog
    val dashboardFeature: StateFlow<DashboardFeature> = presetController.dashboardFeature

    // ── 组合暴露：能力态 ──

    val attachedMode: StateFlow<AttachedMode?> = capabilityController.attachedMode
    val todoState: StateFlow<List<TodoItemView>?> = capabilityController.todoState
    val subagentRuns: StateFlow<List<SubagentRun>> = capabilityController.subagentRuns
    val pendingQuestion: StateFlow<PendingQuestion?> = capabilityController.pendingQuestion

    // ── 组合暴露：流式态 ──

    val streaming: StateFlow<StreamingState?> = streamingController.streaming
    val isGenerating: StateFlow<Boolean> = streamingController.isGenerating
    val pendingCount: StateFlow<Int> = streamingController.pendingCount

    init {
        viewModelScope.launch { modelController.applyCatalogCache() }
        sessionController.autoTitleDefaultSessions()
        viewModelScope.launch {
            runtimeManager.state.collect { s ->
                if (s is RuntimeState.Running) {
                    modelController.refreshModelCatalog()
                    sessionController.refreshUsageStats()
                    presetController.refreshPresets()
                    sessionController.currentSessionId.value?.let { capabilityController.hydrateCurrentSession(it) }
                    subscribeGlobalEvents()
                    launch { streamingController.flushPending() }
                }
            }
        }
    }

    private fun subscribeGlobalEvents() {
        runtimeManager.rpcClient?.let { eventRouter.attach(it) }
    }

    // ── 动作转发：会话 ──

    fun openSession(id: Long) = sessionController.openSession(id)
    fun closeSession() = sessionController.closeSession()
    fun newSession() = sessionController.newSession()
    fun deleteSession(session: SessionEntity) = sessionController.deleteSession(session)
    fun deleteSessions(sessions: List<SessionEntity>) = sessionController.deleteSessions(sessions)
    fun renameSession(sessionId: Long, title: String) = sessionController.renameSession(sessionId, title)
    fun refreshUsageStats() = sessionController.refreshUsageStats()
    internal fun dshSessionIdOf(roomId: Long?): String = sessionController.dshSessionIdOf(roomId)

    // ── 动作转发：模型/提供商/思考强度 ──

    fun refreshModelCatalog() = modelController.refreshModelCatalog()
    fun selectModel(model: String) =
        modelController.selectModel(model, dshSessionIdOf(sessionController.currentSessionId.value))
    fun selectProvider(provider: String) =
        modelController.selectProvider(provider, dshSessionIdOf(sessionController.currentSessionId.value))
    fun selectReasoningEffort(effort: String) =
        modelController.selectReasoningEffort(effort, dshSessionIdOf(sessionController.currentSessionId.value))
    fun toggleWebSearch(enabled: Boolean) = modelController.toggleWebSearch(enabled)

    // ── 动作转发：工作设置 ──

    fun refreshPresets() = presetController.refreshPresets()
    fun selectDefaultPreset(id: String) = presetController.selectDefaultPreset(id)
    fun deletePreset(id: String) = presetController.deletePreset(id)
    fun switchWorkspace(path: String) = presetController.switchWorkspace(path)
    fun setSessionFilter(mode: String) = presetController.setSessionFilter(mode)
    fun selectDashboardFeature(feature: DashboardFeature) = presetController.selectDashboardFeature(feature)

    // ── 动作转发：附加模式 / 问答 ──

    fun attachPlan() = capabilityController.attachPlan()
    fun detachPlan() = capabilityController.detachPlan()
    fun attachGoal(objective: String) = capabilityController.attachGoal(objective)
    fun detachGoal() = capabilityController.detachGoal()
    fun answerQuestion(requestId: String, answers: List<DshParams.QuestionAnswer>) =
        capabilityController.answerQuestion(requestId, answers)
    fun cancelQuestion(requestId: String) = capabilityController.cancelQuestion(requestId)

    // ── 动作转发：发送/流式 ──

    fun sendMessage(text: String, attachments: List<PendingAttachment> = emptyList()) =
        streamingController.sendMessage(text, attachments)
    fun stopGenerating() = streamingController.stopGenerating()

    // ── 工具 ──

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

}