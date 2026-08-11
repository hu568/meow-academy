package com.meow.academy.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** 枚举 ↔ 字符串 转换器 */
class Converters {
    @TypeConverter
    fun roleToString(role: MessageRole): String = role.name

    @TypeConverter
    fun stringToRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun statusToString(status: MessageStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}

/** 聊天数据库（M2.4：会话 + 消息两表，先做到存得下读得出） */
@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "meow_chat.db",
                ).build().also { instance = it }
            }
    }
}
