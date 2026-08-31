package com.meow.academy.ui.chat

import com.meow.academy.data.model.DEEPSEEK_PROVIDER
import com.meow.academy.data.model.ModelCatalogRepository
import com.meow.academy.data.model.ProviderProfile
import com.meow.academy.data.model.buildProviderDirectory
import com.meow.academy.data.model.parseCatalogProfiles
import com.meow.academy.data.settings.ChatBackground
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.data.settings.ThemeConfigRepository
import com.meow.academy.data.settings.resolveChatBackground
import com.meow.academy.rpc.LlmModelInfo
import com.meow.academy.rpc.LlmProviderInfo
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 模型/提供商/思考强度/聊天底图状态控制器（plan-chatviewmodel-refactor §2.1）。
 *
 * 状态所有权：llmModel / reasoningEffort / webSearchEnabled / supportedEfforts /
 * providers / availableModels / currentProvider / chatBackground / modelCatalogMap。
 */
class ChatModelController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val modelCatalog: ModelCatalogRepository,
    private val runtimeManager: RuntimeManager,
    private val themeConfigRepository: ThemeConfigRepository,
    private val appConfigDir: java.io.File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val DEFAULT_MODELS = listOf("deepseek-v4-flash", "deepseek-v4-pro")

    // ── 工具栏设置（模型 / 思考强度 / 网络搜索 / 聊天底图） ──

    val llmModel: StateFlow<String> = settingsRepository.llmModel
        .stateIn(scope, SharingStarted.Eagerly, "deepseek-v4-flash")
    val reasoningEffort: StateFlow<String> = settingsRepository.reasoningEffort
        .stateIn(scope, SharingStarted.Eagerly, "high")
    val webSearchEnabled: StateFlow<Boolean> = settingsRepository.webSearchEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

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
        themeConfigRepository.config,
    ) { dynamic, dsRaw, configRaw ->
        resolveChatBackground(dynamic, dsRaw, configRaw, appConfigDir)
    }.stateIn(scope, SharingStarted.Eagerly, ChatBackground.None)

    // ── 模型管理：可切换 provider 与模型列表（M4，从 DSH RPC 读取） ──

    private val _providers = MutableStateFlow<List<LlmProviderInfo>>(emptyList())
    val providers: StateFlow<List<LlmProviderInfo>> = _providers.asStateFlow()

    private val _availableModels = MutableStateFlow(DEFAULT_MODELS)
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    /** modelId → 模型信息（含 inputModalities），发送图片时判断当前模型是否支持视觉 */
    private val _modelCatalog = MutableStateFlow<Map<String, LlmModelInfo>>(emptyMap())
    val modelCatalogMap: Map<String, LlmModelInfo> get() = _modelCatalog.value

    /** 当前 provider（DataStore "deepseek" → DSH 路由 "deepseek-official"） */
    val currentProvider: StateFlow<String> = settingsRepository.llmProvider
        .map { if (it == "deepseek") "deepseek-official" else it }
        .stateIn(scope, SharingStarted.Eagerly, "deepseek-official")

    init {
        // 模型管理里调整过的 provider 顺序实时同步到聊天工具栏
        scope.launch {
            settingsRepository.providerOrder.collect { order ->
                _providers.value = reorderLlmProviders(_providers.value, order)
            }
        }
    }

    // ── 缓存优先渲染 ──

    /**
     * 缓存优先渲染：从本地缓存构建工具栏（无缓存时至少显示内置 DeepSeek）。
     * provider 目录与设置页共用同一份（内置 DeepSeek + 预设 + 已配置 provider），
     * 优先用 providersJson 缓存（刷新时存的同一份共享目录），其次由 settingsDescribe 缓存构建；
     * 模型列表来自 settingsDescribe 缓存。
     */
    suspend fun applyCatalogCache() {
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
    fun buildProviderList(
        profiles: Map<String, ProviderProfile>,
        disabled: Set<String>,
        order: List<String>,
    ): List<LlmProviderInfo> =
        buildProviderDirectory(profiles, disabled, order).map { e ->
            LlmProviderInfo(provider = e.key, displayName = e.displayName, registered = e.registered)
        }

    /** 把本地缓存里的 provider 目录按用户自定义顺序重排（缺失 key 追加到尾部） */
    fun reorderLlmProviders(
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

    /** 应用 provider 顺序变化（从 init 收集器调用） */
    fun applyProviderOrder(order: List<String>) {
        _providers.value = reorderLlmProviders(_providers.value, order)
    }

    // ── 刷新 provider 目录 + 模型列表（DSH 就绪后调） ──

    /** 刷新 provider 目录 + 当前 provider 的模型列表（进入聊天页/运行时启动后调用） */
    fun refreshModelCatalog() {
        scope.launch {
            val c = runtimeManager.rpcClient ?: return@launch
            val disabled = settingsRepository.disabledProviders.first()
            val order = settingsRepository.providerOrder.first()
            val desc = c.settingsDescribe("llm-pi-ai")
            val profiles = desc?.let { runCatching { parseCatalogProfiles(it) }.getOrNull() } ?: emptyMap()
            val providers = buildProviderList(profiles, disabled, order)
            _providers.value = providers
            // 同步写本地缓存
            runCatching {
                modelCatalog.saveProviders(json.encodeToString(ListSerializer(LlmProviderInfo.serializer()), providers))
            }
            desc?.let { resp -> runCatching { modelCatalog.saveCatalog(resp.toString()) } }
            val p = currentProvider.value
            val models = c.llmModels(p) ?: emptyList()
            _availableModels.value = if (models.isEmpty()) DEFAULT_MODELS else models.map { it.id }
            _modelCatalog.value = models.associateBy { it.id }
            // 当前模型的思考档位能力
            syncSupportedEfforts(models.firstOrNull { it.id == llmModel.value }?.reasoning?.efforts)
        }
    }

    // ── 模型/提供商/思考强度切换 ──

    /** 切换模型：更新全局默认 + 当前会话立即生效（session/setModel） */
    fun selectModel(model: String, dshSessionId: String) {
        scope.launch {
            settingsRepository.setLlmModel(model)
            syncSupportedEfforts(_modelCatalog.value[model]?.reasoning?.efforts)
            (runtimeManager.rpcClient)?.setModel(dshSessionId, model = model)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /** 切换 provider：更新默认 + 选第一个模型 + 当前会话立即生效 */
    fun selectProvider(provider: String, dshSessionId: String) {
        scope.launch {
            val c = runtimeManager.rpcClient
            val models = c?.llmModels(provider) ?: emptyList()
            val first = if (models.isEmpty()) DEFAULT_MODELS.first() else models.first().id
            settingsRepository.setLlmProvider(provider)
            settingsRepository.setLlmModel(first)
            _availableModels.value = if (models.isEmpty()) DEFAULT_MODELS else models.map { it.id }
            syncSupportedEfforts(models.firstOrNull { it.id == first }?.reasoning?.efforts)
            c?.setModel(dshSessionId, provider = provider, model = first)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /** 切换思考强度：更新全局默认 + 当前会话立即生效（session/setModel） */
    fun selectReasoningEffort(effort: String, dshSessionId: String) {
        scope.launch {
            settingsRepository.setReasoningEffort(effort)
            runtimeManager.rpcClient?.setModel(dshSessionId, reasoningEffort = effort)
                ?.let { syncEffectiveEffort(it) }
        }
    }

    /** 切换网络搜索：写设置 + 重启 DSH（SQLite 持久化后会话自动 resume） */
    fun toggleWebSearch(enabled: Boolean) {
        scope.launch {
            settingsRepository.setWebSearchEnabled(enabled)
            runtimeManager.restart()
        }
    }

    /**
     * setModel 响应里服务端钳制后的思考强度同步回本地全局默认（工具栏与新会话保持一致）。
     * 服务端对不支持思考的模型会「不传强度」（selection 无 reasoningEffort），
     * 本地用 'off' 表达该状态（UI 显示「思考·关」，且 initialize 带的 off 也会被服务端钳掉）。
     * 响应携带的 modelReasoning（模型支持档位）同步进 [supportedEfforts]。
     */
    private suspend fun syncEffectiveEffort(result: JsonObject?) {
        val sel = result?.get("selection")?.jsonObject ?: return
        val effort = sel["reasoningEffort"]?.jsonPrimitive?.contentOrNull ?: "off"
        settingsRepository.setReasoningEffort(effort)
        result["modelReasoning"]?.jsonObject?.get("efforts")?.jsonArray?.let { arr ->
            syncSupportedEfforts(arr.mapNotNull { it.jsonPrimitive.contentOrNull })
        }
    }

    /** 思考档位同步入口：null = 无能力数据（按 provider 兜底）；空列表 = 明确不支持思考（按钮禁用） */
    fun syncSupportedEfforts(efforts: List<String>?) {
        _supportedEfforts.value = efforts
            ?: if (currentProvider.value == DEEPSEEK_PROVIDER) listOf("off", "high", "max") else emptyList()
    }
}