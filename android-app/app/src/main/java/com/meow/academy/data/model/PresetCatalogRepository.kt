package com.meow.academy.data.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meow.academy.rpc.DshRpcClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Agent 预设目录本地缓存（DataStore Preferences，仿 [ModelCatalogRepository]）。
 *
 * 前后端解耦：DSH 的 presets/list 结果（plan-standard-mode §3.1）落本地，
 * 右侧看板「工作设置 → Agent 预设」打开即用缓存渲染，DSH 就绪后再 refresh() 覆盖——
 * 不等 DSH 进程加载完成才显示内容；DSH 未就绪 / 请求失败时静默保留缓存。
 */
private val Context.presetCatalogDataStore by preferencesDataStore(name = "meow_preset_catalog")

/** Agent 预设条目（presets/list 响应 shape，plan-standard-mode §3.1） */
@Serializable
data class PresetEntry(
    val id: String,
    /** preset.yml 的 name（缺省时 UI 回退显示 id） */
    val name: String? = null,
    /** preset.yml 的 description（卡片说明） */
    val description: String? = null,
    /** trust: "system"（内置播种）/ "user"（用户根自定义，可删除） */
    val trust: String? = null,
    /** 解析失败原因；null = 正常（broken 预设 UI 灰显） */
    val broken: String? = null,
    /** 是否当前默认预设（服务端按 roster.resolve 判定） */
    val isDefault: Boolean = false,
)

class PresetCatalogRepository(private val context: Context) {

    private object Keys {
        /** presets/list 结果的 JSON 数组字符串（PresetEntry 列表） */
        val PRESETS_JSON = stringPreferencesKey("presets_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 缓存的预设目录原始 JSON（PresetEntry 数组）；从未同步过时为 null */
    val presetsJson: Flow<String?> = context.presetCatalogDataStore.data.map { it[Keys.PRESETS_JSON] }

    /** 缓存的预设列表（未同步 / 解析失败 → 空列表，UI 据此渲染占位卡片） */
    val presets: Flow<List<PresetEntry>> = presetsJson.map { raw ->
        raw?.let { text -> runCatching { json.decodeFromString<List<PresetEntry>>(text) }.getOrNull() }
            ?: emptyList()
    }

    /**
     * 从 DSH 拉取 presets/list 并覆盖缓存（触发时机归 UI 层：进工作设置页 + DSH 转 Running）。
     *
     * DSH 未就绪（client 为 null）/ 超时 / jsonrpc error → 静默返回，保留缓存不崩溃喵。
     */
    suspend fun refresh(client: DshRpcClient?) {
        val result = client?.presetsList() ?: return
        val entries = decodePresetList(result) ?: return
        runCatching { savePresets(json.encodeToString(entries)) }
    }

    /** 覆盖写缓存（一般经 [refresh]；UI 也可手动写） */
    suspend fun savePresets(entriesJson: String) {
        context.presetCatalogDataStore.edit { it[Keys.PRESETS_JSON] = entriesJson }
    }

    /** 解析 presets/list 的 result（presets 数组）；shape 不对时返回 null（保留旧缓存） */
    private fun decodePresetList(result: JsonObject): List<PresetEntry>? {
        val arr = result["presets"] as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(PresetEntry.serializer(), el) }.getOrNull()
        }
    }
}
