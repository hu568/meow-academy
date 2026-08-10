package com.meow.academy.data.settings

import android.content.Context
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
}
