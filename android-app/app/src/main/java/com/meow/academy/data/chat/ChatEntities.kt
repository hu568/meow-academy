package com.meow.academy.data.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 会话 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 消息角色 */
enum class MessageRole { USER, ASSISTANT }

/**
 * 消息状态：
 * - [STREAMING] 正在流式生成（UI 增量渲染）
 * - [DONE] 完成
 * - [ERROR] 出错
 */
enum class MessageStatus { STREAMING, DONE, ERROR }

/** 单条消息（含 thinking 折叠区与工具调用 JSON） */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: MessageRole,
    val content: String = "",
    val thinking: String = "",
    /** 工具调用记录（JSON 数组字符串：{toolName, arguments, result, isError}[]） */
    val toolCallsJson: String? = null,
    val status: MessageStatus = MessageStatus.DONE,
    val createdAt: Long = System.currentTimeMillis(),
)
