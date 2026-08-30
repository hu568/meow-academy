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
    /**
     * 会话归属的 Agent 预设 id（新会话创建时写定，此后不更新；随首条消息携带给 DSH 定死归属，
     * plan-standard-mode §3.4）。null = v3 前旧数据（视为默认预设）。
     */
    val presetId: String? = null,
    /**
     * 会话归属的工作区绝对路径（新会话创建时写定，此后不更新；null = v3 前旧数据，
     * 迁移时回填为 filesDir/workspace——v3 前所有会话都在唯一工作区）。
     */
    val workspacePath: String? = null,
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
    /** 工具调用记录（JSON 数组字符串：{toolName, arguments, result, isError}[]）；旧字段，新消息改用 [segmentsJson] */
    val toolCallsJson: String? = null,
    /**
     * 有序步骤序列（JSON 数组字符串）：思考段与工具调用按 DSH 事件到达顺序交错。
     * 元素：{"type":"reasoning","text":...} 或 {"type":"tool","id","name","arguments","result","isError"}。
     * 为 null 表示旧消息，渲染时回退到 thinking + toolCallsJson。
     */
    val segmentsJson: String? = null,
    val status: MessageStatus = MessageStatus.DONE,
    val createdAt: Long = System.currentTimeMillis(),
)
