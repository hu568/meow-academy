package com.meow.academy.ui.chat

import com.meow.academy.rpc.DshEvent
import com.meow.academy.rpc.DshEventTypes
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.DshRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * DSH 事件单点路由器（拆 ChatViewModel 屎山的最大收益点，plan-chatviewmodel-refactor §2.3）。
 *
 * 职责：
 * - 单点订阅 [DshRpcClient.events]（按 rpc 实例同一性重挂——重连/重启后 events 是新 SharedFlow，
 *   Running 状态反复触发也不会重复订阅）；
 * - 能力事件（todo/plan/goal/问答/subagent）按类型路由给 [ChatCapabilityController]——全局单点，
 *   杜绝「runStream 收集器 + 全局收集器两处处理」的重复分支；
 * - 流式回合事件（assistant/chunk、tool/call、tool/result）**不在这里路由**：
 *   仍由 runStream 内部的 per-session 收集器消费（含 turn/end 回合边界，见 ChatStreamingController）。
 *
 * 魔法字符串收敛：subagent.started/finished 已收进 DshEventTypes.SUBAGENT_STARTED/FINISHED。
 */
class ChatEventRouter(
    private val scope: CoroutineScope,
    private val capability: ChatCapabilityController,
) {
    private var subscribedClient: DshRpcClient? = null
    private var job: Job? = null

    /** 按 rpc 实例同一性重挂（与旧 subscribeGlobalEvents 同款语义：Running 反复触发不重复订阅） */
    fun attach(rpc: DshRpcClient) {
        if (rpc === subscribedClient) return
        job?.cancel()
        subscribedClient = rpc
        job = scope.launch {
            rpc.events.collect { ev -> route(ev) }
        }
    }

    private fun route(ev: DshEvent) = runCatching {
        when (ev.type) {
            DshEventTypes.TODO_WRITE,
            DshEventTypes.PLAN_MODE,
            DshEventTypes.GOAL_CHANGE,
            DshNotifMethods.SESSION_QUESTION,
            DshEventTypes.SUBAGENT_STARTED,
            DshEventTypes.SUBAGENT_FINISHED,
            -> capability.onCapabilityEvent(ev)
            else -> Unit // 其余事件（流式回合等）由各自的 per-session 收集器消费
        }
    }
}
