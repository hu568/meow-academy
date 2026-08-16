package com.meow.academy.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.rpc.DshParams
import com.meow.academy.rpc.DshRpcClient
import com.meow.academy.rpc.LlmModelInfo
import com.meow.academy.rpc.LlmModelInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** provider 配置 profile（settings/describe 的 value.providers.<name>） */
data class ProviderProfile(
    val displayName: String? = null,
    val api: String? = null,
    val baseURL: String? = null,
    val models: List<ModelProfile> = emptyList(),
)

/** provider 下单个模型的扩展配置 */
data class ModelProfile(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
)

/** 模型管理列表条目（内置 DeepSeek + 常见 provider + 用户自定义） */
data class ProviderListItem(
    val key: String,
    val displayName: String,
    val baseURL: String?,
    val modelCount: Int,
    val registered: Boolean,
    val isBuiltin: Boolean,
)

/** provider 显示名 → 路由名（settings dict key）的 slug 化 */
fun slug(name: String): String =
    name.lowercase().trim().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "provider" }

/** 常见 OpenAI 兼容 provider 预设（直接展示在列表，baseURL 预填） */
val PRESETS: List<Pair<String, String>> = listOf(
    "OpenAI" to "https://api.openai.com/v1",
    "Moonshot (Kimi)" to "https://api.moonshot.cn/v1",
    "Groq" to "https://api.groq.com/openai/v1",
    "硅基流动 SiliconFlow" to "https://api.siliconflow.cn/v1",
    "通义千问 Qwen" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
)

/**
 * 模型管理页 ViewModel：provider 列表（内置 + 常见 + 自定义）+ profile 读写。
 * 默认模型用本地 StateFlow 保证 UI 即时反馈（避免 DataStore 读延迟导致的点击竞态）。
 */
class ModelManageViewModel(app: Application) : AndroidViewModel(app) {

    private val runtimeManager = (app as MeowAcademyApp).runtimeManager
    private val settingsRepository = (app as MeowAcademyApp).settingsRepository
    private val json = Json { ignoreUnknownKeys = true }

    /** 列表条目（内置 + 常见 + 自定义） */
    private val _items = MutableStateFlow<List<ProviderListItem>>(emptyList())
    val items: StateFlow<List<ProviderListItem>> = _items.asStateFlow()

    /** provider 名 → profile（baseURL/models 等） */
    private val _profiles = MutableStateFlow<Map<String, ProviderProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, ProviderProfile>> = _profiles.asStateFlow()

    /** 禁用的 provider 名集合（DataStore） */
    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    val disabled: StateFlow<Set<String>> = _disabled.asStateFlow()

    /** 当前默认 provider / model（本地权威状态） */
    private val _llmProvider = MutableStateFlow("deepseek")
    private val _llmModel = MutableStateFlow("deepseek-v4-flash")
    val llmProvider: StateFlow<String> = _llmProvider.asStateFlow()
    val llmModel: StateFlow<String> = _llmModel.asStateFlow()

    /** 内置 DeepSeek 官方 key（DataStore，注入 DEEPSEEK_API_KEY 环境变量） */
    val llmApiKey: StateFlow<String> = settingsRepository.llmApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val client: DshRpcClient? get() = runtimeManager.rpcClient

    init {
        viewModelScope.launch { settingsRepository.disabledProviders.collect { _disabled.value = it } }
        viewModelScope.launch {
            _llmProvider.value = settingsRepository.llmProvider.first()
            _llmModel.value = settingsRepository.llmModel.first()
        }
    }

    /** 拉取 provider 列表 + profile（进入页面时调用） */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val c = client
                if (c != null) {
                    val profiles = parseProfiles(c.settingsDescribe("llm-pi-ai"))
                    _profiles.value = profiles
                    _items.value = buildItems(profiles)
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /** 合并列表：内置 DeepSeek + 常见 provider（baseURL 预填）+ 用户自定义 */
    private fun buildItems(profiles: Map<String, ProviderProfile>): List<ProviderListItem> {
        val list = mutableListOf<ProviderListItem>()
        list.add(ProviderListItem("deepseek-official", "DeepSeek", null, 2, true, true))
        for ((name, url) in PRESETS) {
            val key = slug(name)
            val profile = profiles[key]
            list.add(
                ProviderListItem(
                    key = key,
                    displayName = name,
                    baseURL = profile?.baseURL ?: url,
                    modelCount = profile?.models?.size ?: 0,
                    registered = profile != null,
                    isBuiltin = false,
                )
            )
        }
        // 用户自定义（profiles 里、不在预设里的）
        for ((key, profile) in profiles) {
            if (PRESETS.any { slug(it.first) == key }) continue
            list.add(ProviderListItem(key, profile.displayName ?: key, profile.baseURL, profile.models.size, true, false))
        }
        return list
    }

    suspend fun loadModels(provider: String): List<LlmModelInfo>? = client?.llmModels(provider)

    /** 保存 provider（写 credential + profile；返回错误信息，成功为 null） */
    suspend fun saveProvider(
        provider: String,
        displayName: String,
        baseURL: String,
        api: String,
        models: List<LlmModelInput>,
        apiKey: String,
    ): String? {
        val c = client ?: return "DSH 运行时未启动"
        val resp = c.request(
            "settings/setProvider",
            DshParams.settingsSetProvider(
                provider = provider,
                displayName = displayName.ifBlank { null },
                baseURL = baseURL.ifBlank { null },
                api = api.ifBlank { null },
                models = models,
                apiKey = apiKey.ifBlank { null },
            ),
            timeoutMs = 20_000,
        )
        return if (resp?.ok == true) null else (resp?.error?.message ?: "保存失败")
    }

    /** 删除 provider（返回错误信息，成功为 null） */
    suspend fun removeProvider(provider: String): String? {
        val c = client ?: return "DSH 运行时未启动"
        val resp = c.request("settings/removeProvider", DshParams.settingsRemoveProvider(provider), timeoutMs = 20_000)
        return if (resp?.ok == true) null else (resp?.error?.message ?: "删除失败")
    }

    /** 获取远端模型列表（provider 非空时后端走已存 credentials 兜底，key 留空也能用） */
    suspend fun discoverModels(provider: String?, baseURL: String, apiKey: String): Result<List<LlmModelInfo>> {
        val c = client ?: return Result.failure(IllegalStateException("DSH 运行时未启动"))
        val resp = c.request(
            "llm/discoverModels",
            DshParams.llmDiscoverModels(provider, baseURL.ifBlank { null }, "openai-completions", apiKey.ifBlank { null }),
            timeoutMs = 30_000,
        )
        if (resp?.ok != true) return Result.failure(IllegalStateException(resp?.error?.message ?: "连接失败"))
        val arr = resp.result?.get("models")?.jsonArray ?: return Result.success(emptyList())
        val models = arr.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(LlmModelInfo.serializer(), el) }.getOrNull()
        }
        return Result.success(models)
    }

    /** 测试单个模型连通性（发一个最小 chat 请求） */
    suspend fun testModel(provider: String, model: String): Result<String> {
        val c = client ?: return Result.failure(IllegalStateException("DSH 运行时未启动"))
        val resp = c.request("llm/testModel", DshParams.testModel(provider, model), timeoutMs = 60_000)
        if (resp?.ok != true) return Result.failure(IllegalStateException(resp?.error?.message ?: "测试失败"))
        return Result.success("连接成功")
    }

    /** 保存内置 DeepSeek key */
    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setLlmApiKey(key) }
    }

    /** 星标切换默认：点击非默认 → 设为默认；点击当前默认 → 取消（回退内置 DeepSeek）。本地 StateFlow 即时更新 */
    fun toggleDefault(provider: String, model: String) {
        val curP = _llmProvider.value.let { if (it == "deepseek") "deepseek-official" else it }
        val curM = _llmModel.value
        val (np, nm) = if (curP == provider && curM == model) "deepseek" to "deepseek-v4-flash" else provider to model
        _llmProvider.value = np
        _llmModel.value = nm
        viewModelScope.launch {
            settingsRepository.setLlmProvider(np)
            settingsRepository.setLlmModel(nm)
        }
    }

    /** 启用/禁用（写 DataStore 禁用集合） */
    fun toggleDisabled(provider: String, disabled: Boolean) {
        viewModelScope.launch { settingsRepository.setProviderDisabled(provider, disabled) }
    }

    private fun parseProfiles(result: JsonObject?): Map<String, ProviderProfile> {
        if (result == null) return emptyMap()
        val namespaces = result["namespaces"]?.jsonArray ?: return emptyMap()
        val ns = namespaces.firstOrNull()?.jsonObject ?: return emptyMap()
        val value = ns["value"]?.jsonObject ?: return emptyMap()
        val providers = value["providers"]?.jsonObject ?: return emptyMap()
        val map = mutableMapOf<String, ProviderProfile>()
        for ((name, profileEl) in providers) {
            val p = profileEl.jsonObject
            val models = p["models"]?.jsonArray?.mapNotNull { m ->
                val mo = m.jsonObject
                val id = mo["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ModelProfile(
                    id = id,
                    name = mo["name"]?.jsonPrimitive?.content,
                    contextWindow = mo["contextWindow"]?.jsonPrimitive?.content?.toIntOrNull(),
                    maxTokens = mo["maxTokens"]?.jsonPrimitive?.content?.toIntOrNull(),
                )
            } ?: emptyList()
            map[name] = ProviderProfile(
                displayName = p["displayName"]?.jsonPrimitive?.content,
                api = p["api"]?.jsonPrimitive?.content,
                baseURL = p["baseURL"]?.jsonPrimitive?.content,
                models = models,
            )
        }
        return map
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer { ModelManageViewModel((this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)) }
        }
    }
}
