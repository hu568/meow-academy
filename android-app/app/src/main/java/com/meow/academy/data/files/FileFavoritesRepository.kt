package com.meow.academy.data.files

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 文件收藏持久化：独立 DataStore，单 key 存 JSON 路径数组，数组顺序即收藏顺序（新收藏置顶，喵~） */
private val Context.fileFavoritesDataStore by preferencesDataStore(name = "file_favorites")

/**
 * 文件收藏（快捷方式）仓库。
 *
 * 只存绝对路径列表；展示用条目（名字/是否目录/存在性）由 ViewModel 按路径实时解析，
 * 文件被删除后对应收藏自动失效不出现在抽屉里。
 */
class FileFavoritesRepository(private val context: Context) {

    private object Keys {
        val FAVORITE_PATHS = stringPreferencesKey("favorite_paths")
    }

    /** 收藏的绝对路径列表（收藏顺序，最新在前） */
    val favoritePaths: Flow<List<String>> = context.fileFavoritesDataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_PATHS]?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** 一次性读取当前收藏列表 */
    suspend fun current(): List<String> = favoritePaths.first()

    /** 覆盖保存收藏列表（去重） */
    suspend fun setFavorites(paths: List<String>) {
        context.fileFavoritesDataStore.edit { it[Keys.FAVORITE_PATHS] = Json.encodeToString(paths.distinct()) }
    }
}
