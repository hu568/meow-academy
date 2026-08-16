package com.meow.academy.data.chat

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 会话 + 消息 DAO（Room） */
@Dao
interface ChatDao {

    // ── 会话 ──

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    // ── 消息 ──

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String, status: MessageStatus)

    @Query("UPDATE messages SET thinking = :thinking WHERE id = :id")
    suspend fun updateMessageThinking(id: Long, thinking: String)

    @Query("UPDATE messages SET toolCallsJson = :toolCallsJson WHERE id = :id")
    suspend fun updateMessageTools(id: Long, toolCallsJson: String?)

    @Query("UPDATE messages SET segmentsJson = :segmentsJson WHERE id = :id")
    suspend fun updateMessageSegments(id: Long, segmentsJson: String?)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: Long)

    /** 启动兜底：进程被杀后残留的 STREAMING 消息收不了尾，标记为 ERROR（保留已有内容） */
    @Query("UPDATE messages SET status = 'ERROR', content = CASE WHEN content = '' THEN '⚠️ 生成被中断' ELSE content END WHERE status = 'STREAMING'")
    suspend fun cleanupStaleStreaming()
}
