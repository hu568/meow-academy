package com.meow.academy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.meow.academy.data.settings.CHAT_BG_NONE
import com.meow.academy.data.settings.DEFAULT_THEME_SEED_ARGB
import com.meow.academy.data.settings.HomeTab
import com.meow.academy.data.settings.ResidentMode
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.data.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页 ViewModel：把 DataStore 的 Flow 暴露为 StateFlow，并提供写操作 */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = repository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** 自定义主题种子色（ARGB Long，CUSTOM 模式使用） */
    val themeSeedColor: StateFlow<Long> = repository.themeSeedColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_THEME_SEED_ARGB)

    /** 聊天底图（"none" / "preset:<id>" / "file:<路径>"） */
    val chatBackground: StateFlow<String> = repository.chatBackground
        .stateIn(viewModelScope, SharingStarted.Eagerly, CHAT_BG_NONE)

    /** 「使用动态配置」背景管理开关 */
    val backgroundDynamicEnabled: StateFlow<Boolean> = repository.backgroundDynamicEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val defaultHome: StateFlow<HomeTab> = repository.defaultHome
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeTab.CHAT)

    val residentMode: StateFlow<ResidentMode> = repository.residentMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResidentMode.OFF)

    val residentMinutes: StateFlow<Int> = repository.residentMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, 15)

    // ── 模型配置（M2.6 雏形） ──
    val llmProvider: StateFlow<String> = repository.llmProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "deepseek")

    val llmModel: StateFlow<String> = repository.llmModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "deepseek-v4-flash")

    val llmApiKey: StateFlow<String> = repository.llmApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val reasoningEffort: StateFlow<String> = repository.reasoningEffort
        .stateIn(viewModelScope, SharingStarted.Eagerly, "high")

    val webSearchEnabled: StateFlow<Boolean> = repository.webSearchEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        repository.setThemeMode(mode)
    }

    fun setThemeSeedColor(argb: Long) = viewModelScope.launch {
        repository.setThemeSeedColor(argb)
    }

    fun setChatBackground(raw: String) = viewModelScope.launch {
        repository.setChatBackground(raw)
    }

    fun setBackgroundDynamicEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.setBackgroundDynamicEnabled(enabled)
    }

    fun setDefaultHome(tab: HomeTab) = viewModelScope.launch {
        repository.setDefaultHome(tab)
    }

    fun setResidentMode(mode: ResidentMode) = viewModelScope.launch {
        repository.setResidentMode(mode)
    }

    fun setResidentMinutes(minutes: Int) = viewModelScope.launch {
        repository.setResidentMinutes(minutes)
    }

    fun setLlmProvider(provider: String) = viewModelScope.launch {
        repository.setLlmProvider(provider)
    }

    fun setLlmModel(model: String) = viewModelScope.launch {
        repository.setLlmModel(model)
    }

    fun setLlmApiKey(key: String) = viewModelScope.launch {
        repository.setLlmApiKey(key)
    }

    fun setReasoningEffort(effort: String) = viewModelScope.launch {
        repository.setReasoningEffort(effort)
    }

    fun setWebSearchEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.setWebSearchEnabled(enabled)
    }

    companion object {
        fun factory(repository: SettingsRepository) = viewModelFactory {
            initializer { SettingsViewModel(repository) }
        }
    }
}
