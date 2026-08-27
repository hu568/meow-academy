package com.meow.academy.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 设置持久化仓库（DataStore Preferences）。
 *
 * M2.1 阶段只存三样：主题模式、默认首页、常驻三档开关（+ 保活分钟数）。
 * 双模型配置等后续里程碑再扩。
 */
private val Context.settingsDataStore by preferencesDataStore(name = "meow_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // 自定义主题的种子色（ARGB Long，0..0xFFFFFFFF；默认喵仓粉紫）
        val THEME_SEED_COLOR = longPreferencesKey("theme_seed_color")
        // 聊天底图："none" / "preset:<id>" / "file:<absPath>"（见 ChatBackgrounds.kt）
        val CHAT_BACKGROUND = stringPreferencesKey("chat_background")
        val DEFAULT_HOME = stringPreferencesKey("default_home")
        val RESIDENT_MODE = stringPreferencesKey("resident_mode")
        val RESIDENT_MINUTES = intPreferencesKey("resident_minutes")
        // 模型配置雏形（M2.6 完善 UI；M2.2 供 RuntimeManager 注入 pi 环境）
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        // 自定义 provider 的 API Key 本地回显缓存（DSH settings/describe 是 redacted，不回传明文）
        val PROVIDER_API_KEYS = stringPreferencesKey("provider_api_keys")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        // 禁用的 provider 名集合（App 层 UI 过滤；DSH 侧仍注册，配置不丢）
        val DISABLED_PROVIDERS = stringSetPreferencesKey("disabled_providers")
        // 用户手动调整后的 provider 顺序（DataStore 本地保存；DSH settings 只按 key 存 profile）
        val PROVIDER_ORDER = stringPreferencesKey("provider_order")
        // 右侧功能看板当前功能页（默认 MODELS；退出 App 后仍记住上次切换的模式）
        val DASHBOARD_FEATURE = stringPreferencesKey("dashboard_feature")
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    /** 自定义主题种子色（ARGB Long，仅 [ThemeMode.CUSTOM] 使用；默认喵仓粉紫） */
    val themeSeedColor: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.THEME_SEED_COLOR] ?: DEFAULT_THEME_SEED_ARGB
    }

    /** 聊天底图持久化字符串（"none" / "preset:<id>" / "file:<absPath>"，默认无背景） */
    val chatBackground: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.CHAT_BACKGROUND] ?: CHAT_BG_NONE
    }

    val defaultHome: Flow<HomeTab> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_HOME]?.let { runCatching { HomeTab.valueOf(it) }.getOrNull() }
            ?: HomeTab.CHAT
    }

    val residentMode: Flow<ResidentMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.RESIDENT_MODE]?.let { runCatching { ResidentMode.valueOf(it) }.getOrNull() }
            ?: ResidentMode.OFF
    }

    /** 有限保活的分钟数，可选 15 / 30 / 60 */
    val residentMinutes: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.RESIDENT_MINUTES] ?: 15
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    /** 保存自定义主题种子色（ARGB Long，0..0xFFFFFFFF） */
    suspend fun setThemeSeedColor(argb: Long) {
        context.settingsDataStore.edit { it[Keys.THEME_SEED_COLOR] = argb and 0xFFFFFFFFL }
    }

    /** 保存聊天底图；从自定义图片切走时顺带清理旧文件，避免私有目录堆积 */
    suspend fun setChatBackground(raw: String) {
        val old = context.settingsDataStore.data.first()[Keys.CHAT_BACKGROUND] ?: CHAT_BG_NONE
        context.settingsDataStore.edit { it[Keys.CHAT_BACKGROUND] = raw }
        if (raw != old) {
            val oldPath = chatBackgroundFilePath(old)
            val newPath = chatBackgroundFilePath(raw)
            if (oldPath != null && oldPath != newPath) {
                runCatching { File(oldPath).delete() }
            }
        }
    }

    suspend fun setDefaultHome(tab: HomeTab) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_HOME] = tab.name }
    }

    suspend fun setResidentMode(mode: ResidentMode) {
        context.settingsDataStore.edit { it[Keys.RESIDENT_MODE] = mode.name }
    }

    suspend fun setResidentMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.RESIDENT_MINUTES] = minutes }
    }

    // ── 模型配置（雏形） ──

    /** LLM provider（默认 deepseek） */
    val llmProvider: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.LLM_PROVIDER] ?: "deepseek"
    }

    /** LLM 模型 id（默认 deepseek-v4-flash，与后端 .env 一致） */
    val llmModel: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.LLM_MODEL] ?: "deepseek-v4-flash"
    }

    /** 禁用的 provider 名集合（模型管理页启用/禁用开关；聊天页据此过滤） */
    val disabledProviders: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DISABLED_PROVIDERS] ?: emptySet()
    }

    suspend fun setProviderDisabled(provider: String, disabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_PROVIDERS] ?: emptySet()
            val next = if (disabled) current + provider else current - provider
            prefs[Keys.DISABLED_PROVIDERS] = next
        }
    }

    /** 用户自定义 provider 顺序（空列表 = 使用默认顺序） */
    val providerOrder: Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PROVIDER_ORDER]?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun setProviderOrder(order: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PROVIDER_ORDER] = Json.encodeToString(order.distinct())
        }
    }

    /** DeepSeek API Key（App 私有存储，注入 DSH 进程环境变量） */
    val llmApiKey: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.LLM_API_KEY] ?: ""
    }

    /** 自定义 provider 名 → API Key（仅用于前端回显；DSH 侧仍以 credential 为准） */
    val providerApiKeys: Flow<Map<String, String>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PROVIDER_API_KEYS]?.let { raw ->
            runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    /** 保存自定义 provider 的 API Key 到本地回显缓存；空串表示移除该 provider 的缓存 */
    suspend fun setProviderApiKey(provider: String, apiKey: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.PROVIDER_API_KEYS]?.let { raw ->
                runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val next = if (apiKey.isBlank()) current - provider else current + (provider to apiKey.trim())
            prefs[Keys.PROVIDER_API_KEYS] = Json.encodeToString(next)
        }
    }

    suspend fun setLlmProvider(provider: String) {
        context.settingsDataStore.edit { it[Keys.LLM_PROVIDER] = provider }
    }

    suspend fun setLlmModel(model: String) {
        context.settingsDataStore.edit { it[Keys.LLM_MODEL] = model }
    }

    suspend fun setLlmApiKey(apiKey: String) {
        context.settingsDataStore.edit { it[Keys.LLM_API_KEY] = apiKey.trim() }
    }

    // ── 思考强度 + 网络搜索（M3.1 聊天工具栏） ──

    /** 思考强度（off/high/max；默认 high，与 llm-deepseek 一致） */
    val reasoningEffort: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.REASONING_EFFORT] ?: "high"
    }

    /** 网络搜索开关（默认关闭） */
    val webSearchEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.WEB_SEARCH_ENABLED] ?: false
    }

    suspend fun setReasoningEffort(effort: String) {
        context.settingsDataStore.edit { it[Keys.REASONING_EFFORT] = effort }
    }

    suspend fun setWebSearchEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WEB_SEARCH_ENABLED] = enabled }
    }

    // ── 右侧功能看板功能页（M6：退出后仍记住上次的模式） ──

    /** 右侧看板当前功能页（DashboardFeature.name，默认 "MODELS"） */
    val dashboardFeature: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.DASHBOARD_FEATURE] ?: "MODELS"
    }

    suspend fun setDashboardFeature(feature: String) {
        context.settingsDataStore.edit { it[Keys.DASHBOARD_FEATURE] = feature }
    }
}