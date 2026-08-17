package com.meow.academy.data.model

/**
 * 模型目录数据模型 + 解析（前后端解耦的本地缓存层）。
 *
 * ProviderProfile / ModelProfile 从 ui/settings/ModelManageModels.kt 挪到这里，
 * 供聊天页工具栏与模型管理页共用：两页都从 DSH settingsDescribe("llm-pi-ai") 的
 * result（value.providers.<name>）解析出各自的视图数据。
 */

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** provider 配置 profile（settingsDescribe value.providers.<name>） */
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

/**
 * 解析 settingsDescribe("llm-pi-ai") 的 result 为 provider 名 → [ProviderProfile]。
 * result 结构：namespaces[].value.providers.<name> = { displayName, api, baseURL, models[] }。
 */
fun parseCatalogProfiles(result: JsonObject?): Map<String, ProviderProfile> {
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
