package com.meow.academy.ui.chat

/**
 * 聊天页共享顶层数据类型（plan-chatviewmodel-refactor §0.1：数据类迁出门面文件，
 * 门面与 UI 同包直接引用，零 import 改动）。
 */

/**
 * 附加模式（会话级能力开关：plan-mode / goal，plan-standard-mode §5.3）。
 *
 * 三态机：[pending] = true 表示附加命令已发出、状态事件（plan/mode、goal/change）未回——
 * 胶囊显示「生效中」转圈；事件到达后 pending 置 false 转确认态。UI 以 `is AttachedMode.Plan` /
 * `is AttachedMode.Goal` 判型，以 [pending] 区分「生效中 vs 已确认」。
 */
sealed interface AttachedMode {
    /** true = 命令已发出、事件未确认（胶囊「生效中」）；false = 事件已确认 */
    val pending: Boolean

    /** 规划模式（/plan 开启） */
    data class Plan(override val pending: Boolean = false) : AttachedMode

    /**
     * 目标模式（/goal <objective> 设定）。
     * @param objective 目标全文（胶囊只显示前 8 字摘要）
     * @param phase 生命周期 phase（active/paused/blocked/complete，GoalSnapshot.phase；水合/事件回填）
     */
    data class Goal(
        val objective: String,
        val phase: String? = null,
        override val pending: Boolean = false,
    ) : AttachedMode
}

/** 悬浮栏 todo 条目视图（todo/write 事件 / session/query 水合的 {content, status}） */
data class TodoItemView(val content: String, val status: String)

/** 悬浮栏子代理运行条目（subagent.started / subagent.finished 通知折叠） */
data class SubagentRun(
    val parentSessionId: String,
    val childSessionId: String,
    val provider: String? = null,
    val status: String? = null,
    val stopReason: String? = null,
    /** 收尾摘要（lastAssistantMessage，仅进程内子代理有） */
    val lastMessage: String? = null,
)

/** 待回答的问答（session.question 通知 → 问答卡交互通道，plan-standard-mode §三.6） */
data class PendingQuestion(
    val requestId: String,
    val sessionId: String?,
    val questions: List<QuestionItem>,
)

/** 单个问题（AskUserQuestionItem 的解析视图；detail = plan 审阅时的计划 Markdown 全文） */
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<OptionItem> = emptyList(),
    val multiSelect: Boolean = false,
    /** intent.kind（'plan-review' 时问答卡走计划审阅样式） */
    val intentKind: String? = null,
    /** intent.approve（plan 审阅批准选项的原文 label，回答按它回传） */
    val intentApprove: String? = null,
)

/** 单个选项（AskUserQuestionOption：label 必填 + 可选一句话说明） */
data class OptionItem(val label: String, val description: String? = null)
