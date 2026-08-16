package com.meow.academy.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        /** v1 → v2：messages 表新增 segmentsJson 列（有序步骤序列），旧数据保留走兼容渲染 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN segmentsJson TEXT")
            }
        }

        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "meow_chat.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
