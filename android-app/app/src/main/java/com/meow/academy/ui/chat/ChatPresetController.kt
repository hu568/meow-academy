package com.meow.academy.ui.chat

import com.meow.academy.data.model.PresetCatalogRepository
import com.meow.academy.data.model.PresetEntry
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 工作设置状态控制器——预设目录/默认预设/工作区/会话过滤/看板页（plan-chatviewmodel-refactor §2.1）。
 *
 * 状态所有权：sessionFilter / defaultPreset / defaultWorkspacePath / presetCatalog / dashboardFeature。
 * 全部转发 DataStore 或预设目录缓存，切换只写设置、不重启 DSH。
 */
class ChatPresetController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val presetCatalogRepo: PresetCatalogRepository,
    private val runtimeManager: RuntimeManager,
    private val defaultWorkspaceAbsPath: String,
    private val toast: (String) -> Unit,
) {
    /** 会话抽屉显示过滤（"all" 全部 / "workspace" 当前工作区；转发 DataStore） */
    val sessionFilter: StateFlow<String> = settingsRepository.sessionFilter
        .stateIn(scope, SharingStarted.Eagerly, "all")

    /** 新会话默认 Agent 预设 id（默认 meow-standard；只对新会话生效） */
    val defaultPreset: StateFlow<String> = settingsRepository.defaultPreset
        .stateIn(scope, SharingStarted.Eagerly, "meow-standard")

    /** 新会话默认工作区绝对路径（默认 filesDir/workspace；切换只写 DataStore，不重启 DSH） */
    val defaultWorkspacePath: StateFlow<String> = settingsRepository.workspacePath
        .stateIn(scope, SharingStarted.Eagerly, defaultWorkspaceAbsPath)

    /** Agent 预设目录（presets/list 缓存；DSH 未就绪时先渲染缓存，refreshPresets 覆盖） */
    val presetCatalog: StateFlow<List<PresetEntry>> = presetCatalogRepo.presets
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 右侧功能看板当前功能页（M6：持久化到 DataStore，退出 App 后仍记住上次的模式） */
    val dashboardFeature: StateFlow<DashboardFeature> = settingsRepository.dashboardFeature
        .map { raw -> runCatching { DashboardFeature.valueOf(raw) }.getOrDefault(DashboardFeature.MODELS) }
        .stateIn(scope, SharingStarted.Eagerly, DashboardFeature.MODELS)

    /**
     * 拉取 presets/list 并覆盖本地缓存（自动扫描接口，App 不硬编码列表）。
     * 触发时机：进工作设置页（看板调用）+ DSH 转 Running；DSH 未就绪 / 失败 → 静默保留缓存。
     */
    fun refreshPresets() {
        scope.launch { presetCatalogRepo.refresh(runtimeManager.rpcClient) }
    }

    /** 设为默认 Agent 预设（只对新会话生效；DataStore） */
    fun selectDefaultPreset(id: String) {
        scope.launch { settingsRepository.setDefaultPreset(id) }
    }

    /**
     * 删除自定义预设（presets/delete，仅 trust=user 服务端放行）。
     * 若删除的是当前默认预设 → 自动回退 meow-standard，避免新会话无预设可用。
     */
    fun deletePreset(id: String) {
        scope.launch {
            val rpc = runtimeManager.rpcClient
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
        scope.launch { settingsRepository.setWorkspacePath(path) }
    }

    /** 切换会话抽屉显示过滤（"all" / "workspace"，DataStore） */
    fun setSessionFilter(mode: String) {
        scope.launch { settingsRepository.setSessionFilter(mode) }
    }

    /** 切换右侧功能看板功能页：持久化到 DataStore，退出 App 后仍记住 */
    fun selectDashboardFeature(feature: DashboardFeature) {
        scope.launch { settingsRepository.setDashboardFeature(feature.name) }
    }
}