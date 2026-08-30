package com.meow.academy.data.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meow.academy.runtime.RuntimeExtractor

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
    version = 3,
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

        /**
         * v2 → v3：sessions 新增 presetId / workspacePath 两列（会话归属：Agent 预设 + 工作区，
         * plan-standard-mode §5.2）。两列都在新会话 insert 时写定、此后不更新，无需 UPDATE DAO。
         * 回填：v3 前所有会话都在唯一工作区 filesDir/workspace → workspacePath 补绝对路径，
         * presetId 留空（视为默认预设）。
         */
        private fun migration2_3(defaultWorkspacePath: String): Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN presetId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN workspacePath TEXT")
                db.execSQL("UPDATE sessions SET workspacePath = ?", arrayOf(defaultWorkspacePath))
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
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        // 回填路径在此取 Application context（迁移回调拿不到 context，构建期先算好）
                        migration2_3(
                            java.io.File(
                                context.applicationContext.filesDir,
                                RuntimeExtractor.WORKSPACE_DIR,
                            ).absolutePath,
                        ),
                    )
                    .build().also { instance = it }
            }
    }
}
