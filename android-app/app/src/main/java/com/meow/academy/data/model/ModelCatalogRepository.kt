package com.meow.academy.data.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 模型目录本地缓存（DataStore Preferences）。
 *
 * 前后端解耦的核心：DSH 拉取的 provider/模型目录（settingsDescribe("llm-pi-ai")
 * result 的 JSON 字符串，redacted 不含明文 key）落本地。
 * UI（聊天工具栏 / 模型管理页）打开即用缓存渲染，DSH 就绪后再后台同步覆盖——
 * 不再等 DSH 进程加载完成才显示内容。
 */
private val Context.modelCatalogDataStore by preferencesDataStore(name = "meow_model_catalog")

class ModelCatalogRepository(private val context: Context) {

    private object Keys {
        /** settingsDescribe("llm-pi-ai") result 的 JSON 字符串（redacted；模型管理页用） */
        val CATALOG_JSON = stringPreferencesKey("catalog_json")

        /** llm/providers 完整目录的 JSON 数组字符串（聊天工具栏用，与真实目录一致） */
        val PROVIDERS_JSON = stringPreferencesKey("providers_json")
    }

    /** 缓存的模型目录 JSON；从未同步过时为 null */
    val catalogJson: Flow<String?> = context.modelCatalogDataStore.data.map { it[Keys.CATALOG_JSON] }

    /** 缓存的 llm/providers 目录 JSON（LlmProviderInfo 数组）；从未同步过时为 null */
    val providersJson: Flow<String?> = context.modelCatalogDataStore.data.map { it[Keys.PROVIDERS_JSON] }

    /** 覆盖写缓存（DSH 拉取成功后调用） */
    suspend fun saveCatalog(json: String) {
        context.modelCatalogDataStore.edit { it[Keys.CATALOG_JSON] = json }
    }

    /** 覆盖写 llm/providers 目录缓存 */
    suspend fun saveProviders(json: String) {
        context.modelCatalogDataStore.edit { it[Keys.PROVIDERS_JSON] = json }
    }
}
