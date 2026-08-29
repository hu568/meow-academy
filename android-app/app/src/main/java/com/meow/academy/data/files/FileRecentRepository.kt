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

/** 文件最近使用持久化：独立 DataStore，单 key 存 JSON 路径数组，数组顺序即最近顺序（最新在前，喵~） */
private val Context.fileRecentsDataStore by preferencesDataStore(name = "file_recents")

/**
 * 文件最近使用记录仓库（与 [FileFavoritesRepository] 同构）。
 *
 * 只存绝对路径列表；展示用条目（名字/大小/存在性）由 ViewModel 按路径实时解析，
 * 文件被删除后对应记录自动失效不出现在「最近使用」里。
 * 记录点：打开文件/图片预览（MainScreen）、聊天快捷面板附加文件（ChatScreen）。
 */
class FileRecentRepository(private val context: Context) {

    private object Keys {
        val RECENT_PATHS = stringPreferencesKey("recent_paths")
    }

    companion object {
        /** 最多保留的最近记录条数（超出丢最旧的） */
        private const val RECENTS_LIMIT = 20
    }

    /** 最近使用的绝对路径列表（最新在前） */
    val recentPaths: Flow<List<String>> = context.fileRecentsDataStore.data.map { prefs ->
        prefs[Keys.RECENT_PATHS]?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** 一次性读取当前最近列表 */
    suspend fun current(): List<String> = recentPaths.first()

    /** 记录一次使用：置顶去重，超出上限丢最旧的 */
    suspend fun record(path: String) {
        context.fileRecentsDataStore.edit { prefs ->
            prefs[Keys.RECENT_PATHS] = Json.encodeToString(
                (listOf(path) + readPaths(prefs)).distinct().take(RECENTS_LIMIT),
            )
        }
    }

    /** 移除单条记录（「最近使用」长按删除用） */
    suspend fun remove(path: String) {
        context.fileRecentsDataStore.edit { prefs ->
            prefs[Keys.RECENT_PATHS] = Json.encodeToString(readPaths(prefs) - path)
        }
    }

    private fun readPaths(prefs: androidx.datastore.preferences.core.Preferences): List<String> =
        prefs[Keys.RECENT_PATHS]?.let { raw ->
            runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
}
