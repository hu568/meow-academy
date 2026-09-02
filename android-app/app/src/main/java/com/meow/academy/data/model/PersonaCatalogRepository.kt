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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 角色库目录本地缓存（DataStore Preferences，仿 [PresetCatalogRepository]）。
 *
 * 前后端解耦：DSH 的 personas/list 结果（plan-memory-execution §1.7）落本地，
 * 角色选择器打开即用缓存渲染，DSH 就绪后再 refresh() 覆盖——
 * 不等 DSH 进程加载完成才显示；DSH 未就绪 / 请求失败时静默保留缓存。
 */
private val Context.personaCatalogDataStore by preferencesDataStore(name = "meow_persona_catalog")

/** 角色条目（personas/list 响应 shape，plan-memory-execution §1.7） */
@Serializable
data class PersonaEntry(
    val id: String,
    /** persona.yml 的 name（缺省时 UI 回退显示 id） */
    val name: String = id,
    /** persona.yml 的 description（角色卡片说明） */
    val description: String = "",
    /** 是否默认角色（id === "default"） */
    val isDefault: Boolean = false,
)

class PersonaCatalogRepository(private val context: Context) {

    private object Keys {
        /** personas/list 结果的 JSON 数组字符串（PersonaEntry 列表） */
        val PERSONAS_JSON = stringPreferencesKey("personas_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 缓存的角色目录原始 JSON（PersonaEntry 数组）；从未同步过时为 null */
    val personasJson: Flow<String?> = context.personaCatalogDataStore.data.map { it[Keys.PERSONAS_JSON] }

    /** 缓存的角色列表（未同步 / 解析失败 → 空列表，UI 据此渲染占位） */
    val personas: Flow<List<PersonaEntry>> = personasJson.map { raw ->
        raw?.let { text -> runCatching { json.decodeFromString<List<PersonaEntry>>(text) }.getOrNull() }
            ?: emptyList()
    }

    /**
     * 从 DSH 拉取 personas/list 并覆盖缓存。
     * DSH 未就绪（client 为 null）/ 超时 / jsonrpc error → 静默返回，保留缓存不崩溃喵。
     */
    suspend fun refresh(client: DshRpcClient?) {
        val result = client?.personasList() ?: return
        val entries = decodePersonaList(result) ?: return
        runCatching { savePersonas(json.encodeToString(entries)) }
    }

    /** 覆盖写缓存（一般经 [refresh]；UI 也可手动写） */
    suspend fun savePersonas(personasJson: String) {
        context.personaCatalogDataStore.edit { it[Keys.PERSONAS_JSON] = personasJson }
    }

    /** 解析 personas/list 的 result（personas 数组）；shape 不对时返回 null（保留旧缓存） */
    private fun decodePersonaList(result: JsonObject): List<PersonaEntry>? {
        val arr = result["personas"] as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(PersonaEntry.serializer(), el) }.getOrNull()
        }
    }
}
