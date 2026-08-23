package com.meow.academy.data.chat

import com.meow.academy.rpc.int as rpcInt
import com.meow.academy.rpc.long as rpcLong
import kotlinx.serialization.json.JsonObject

/** 当前会话调用量统计（session/stats 响应，字段语义对齐 DSH web StatsLine） */
data class SessionUsageStats(
    val turns: Int,
    val steps: Int,
    val llmMs: Long,
    val toolMs: Long,
    val ttftMs: Long,
    val ttftSteps: Int,
    val decodeMs: Long,
    val decodeTokens: Long,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val lastStep: SessionLastStep?,
    val context: SessionContextUsage?,
) {
    companion object {
        /** 解析 session/stats 的 result；result.stats 缺失/损坏返回 null，绝不抛异常 */
        fun parse(json: JsonObject?): SessionUsageStats? {
            val stats = json?.get("stats") as? JsonObject ?: return null
            val lastStep = (stats["lastStep"] as? JsonObject)?.let {
                SessionLastStep(
                    llmMs = it.rpcLong("llmMs") ?: 0L,
                    ttftMs = it.rpcLong("ttftMs") ?: 0L,
                    decodeMs = it.rpcLong("decodeMs") ?: 0L,
                    decodeTokens = it.rpcLong("decodeTokens") ?: 0L,
                )
            }
            val context = (stats["context"] as? JsonObject)?.let {
                val used = it.rpcLong("usedTokens") ?: return@let null
                val window = it.rpcLong("contextWindow") ?: return@let null
                SessionContextUsage(usedTokens = used, contextWindow = window)
            }
            return runCatching {
                SessionUsageStats(
                    turns = stats.rpcInt("turns") ?: 0,
                    steps = stats.rpcInt("steps") ?: 0,
                    llmMs = stats.rpcLong("llmMs") ?: 0L,
                    toolMs = stats.rpcLong("toolMs") ?: 0L,
                    ttftMs = stats.rpcLong("ttftMs") ?: 0L,
                    ttftSteps = stats.rpcInt("ttftSteps") ?: 0,
                    decodeMs = stats.rpcLong("decodeMs") ?: 0L,
                    decodeTokens = stats.rpcLong("decodeTokens") ?: 0L,
                    inputTokens = stats.rpcLong("inputTokens") ?: 0L,
                    cacheReadTokens = stats.rpcLong("cacheReadTokens") ?: 0L,
                    cacheWriteTokens = stats.rpcLong("cacheWriteTokens") ?: 0L,
                    outputTokens = stats.rpcLong("outputTokens") ?: 0L,
                    lastStep = lastStep,
                    context = context,
                )
            }.getOrNull()
        }
    }
}

/** 最近一步（最新回合 footer）：用时/首 token/tok/s 的原始分母 */
data class SessionLastStep(
    val llmMs: Long,
    val ttftMs: Long,
    val decodeMs: Long,
    val decodeTokens: Long,
)

/** 上下文使用量：最近一次请求的 prompt 侧 billed input / 模型上下文窗口 */
data class SessionContextUsage(
    val usedTokens: Long,
    val contextWindow: Long,
)