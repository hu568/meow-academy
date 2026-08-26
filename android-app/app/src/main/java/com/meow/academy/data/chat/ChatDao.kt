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

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    /** 取某会话的第一条用户消息（自动生成标题用） */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER' ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun getFirstUserMessage(sessionId: Long): MessageEntity?

    /** 取尚未自动生成标题的会话（title 为「新会话」的会话） */
    @Query("SELECT * FROM sessions WHERE title = :title")
    suspend fun getSessionsByTitle(title: String): List<SessionEntity>

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    /** 批量删除会话（多选用） */
    @Query("DELETE FROM sessions WHERE id IN (:ids)")
    suspend fun deleteSessionsByIds(ids: List<Long>)

    /** 批量删除多个会话的消息（多选用） */
    @Query("DELETE FROM messages WHERE sessionId IN (:sessionIds)")
    suspend fun deleteMessagesBySessionIds(sessionIds: List<Long>)

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

    /**
     * 启动兜底：进程被杀后残留的 STREAMING 消息收不了尾，标记为 ERROR。
     * - 空内容（正在流式）→ "生成被中断"
     * - 待发送占位（"⏳ 等待 DSH…"）→ "发送中断（运行时未就绪）"
     * - 已有内容（已落库的部分）→ 保留
     */
    @Query("""
        UPDATE messages SET status = 'ERROR',
            content = CASE
                WHEN content = '' THEN '⚠️ 生成被中断'
                WHEN content LIKE '⏳%' THEN '⚠️ 发送中断（运行时未就绪）'
                ELSE content
            END
        WHERE status = 'STREAMING'
    """)
    suspend fun cleanupStaleStreaming()
}
