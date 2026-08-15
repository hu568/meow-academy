package com.meow.academy.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        val DEFAULT_HOME = stringPreferencesKey("default_home")
        val RESIDENT_MODE = stringPreferencesKey("resident_mode")
        val RESIDENT_MINUTES = intPreferencesKey("resident_minutes")
        // 模型配置雏形（M2.6 完善 UI；M2.2 供 RuntimeManager 注入 pi 环境）
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
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

    /** DeepSeek API Key（App 私有存储，注入 DSH 进程环境变量） */
    val llmApiKey: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.LLM_API_KEY] ?: ""
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
}