package com.meow.academy.ui.chat

import com.meow.academy.rpc.DshChunkTypes
import kotlinx.serialization.json.JsonArray

/**
 * 回合分段累积器：把同一回合里**逐 step** 到达的事件按 (turn, step) 归位后再展开成
 * UI / 落库用的有序 segments。
 *
 * ### 为什么需要它（2026-09-12 真机回归「工具调用卡被挤到当前轮最下面」）
 *
 * 0.1.5-rc.2 的事件配方是**按 step 分组**的（PC 冒烟 + 真机 logcat 双向实测，plan-dsh-upgrade §12）：
 * ```
 * assistant/message(step 1, content=[reasoning, tool-call…])   ← 权威块序，先到
 * tool/call(step 1) ×N → tool/result(step 1) ×N               ← ⚠️ 权威 message 里已经登记过这些调用
 * assistant/message(step 2, …) → tool/call ×N → tool/result ×N
 * …
 * assistant/message(step K, content=[text])                   ← 最终正文
 * turn/end
 * ```
 * 旧实现把每个事件都直接追加/插入到**同一条** segments 上，于是两处结构性错误：
 *  1. [mergeAssistantMessage] 每次调用都从游标 0 重新找对齐点 → 后一个 step 的块（与前一步
 *     文本无前缀关系）找不到对齐就**插到最前面** → 末段正文跑到最上面、工具组被挤到当前轮最下面；
 *  2. `tool/call` 是**盲目追加**，与权威 message 已登记的 tool-call 块重复 → 真机实测 2 次调用
 *     渲染成「工具调用 x4」，落库 segmentsJson 里同 callId 段出现两次。
 * （真机实锤：`[text(末段正文), tool(read), reasoning, tool(bash), tool(bash 重复), tool(read 重复)]`。）
 *
 * ### 归位规则（全部幂等、可重入；事件重复送达也不会长出重复段）
 *  - `assistant/message`：content 是**该 step 的权威块序**，用 [mergeAssistantMessage] 合并进该
 *    step 自己的桶（保留 `tool/result` 已回填的结果、保留流式半截文本，不删除已展示段）；
 *  - `tool/call`：按 callId **upsert**（权威 message 已登记过同一调用时只补空的 name/arguments）；
 *  - `tool/result`：按 callId 回填（结果比调用先到时先记账，等该调用登记后补上）；
 *  - `assistant/chunk`（本基线不再发，保留兼容）：增量落到该 step 桶的尾段。
 *
 * 展开 = 已知 (turn, step) 的桶按数值升序 + 缺号桶按到达顺序追加在末尾，即事件日志的时间序。
 */
class TurnSegmentBuilder {

    /**
     * 归位键。(turn, step) 齐全时用真实值；缺失时（DSH 未来若去掉这两个字段）用 [synthetic]
     * 桶按到达顺序追加在末尾——保守退化成「追加不插入」，不重蹈「插到最前面」的覆辙。
     */
    private data class StepKey(val turn: Int, val step: Int, val synthetic: Boolean = false)

    /** 展开顺序（显式维护：真实键按数值插入，缺号键追加在末尾） */
    private val order = mutableListOf<StepKey>()

    /** step → 该 step 的有序分段（不可变列表，替换式更新） */
    private val buckets = HashMap<StepKey, List<Segment>>()

    /** 先到但还没有对应调用段的 `tool/result`（按 callId 记账，调用登记后补上） */
    private val pendingResults = LinkedHashMap<String, Pair<String?, Boolean>>()

    private var syntheticSeq = 0

    /** `assistant/message` 的权威块 → 该 step 的最终块序 */
    fun onAssistantMessage(turn: Int?, step: Int?, blocks: JsonArray?): List<Segment> {
        val incoming = assistantMessageSegments(blocks)
        if (incoming.isEmpty()) return segments()
        val key = messageKey(turn, step)
        put(key, mergeAssistantMessage(bucket(key), incoming))
        drainPendingResults()
        return segments()
    }

    /** `tool/call` → 该 step 的工具段（同 callId 幂等，绝不重复追加） */
    fun onToolCall(turn: Int?, step: Int?, call: ToolCallInfo): List<Segment> {
        val hit = findTool(call.id)
        if (hit != null) {
            val (key, index) = hit
            val old = (buckets.getValue(key)[index] as Segment.Tool).call
            // 只补各自提供的字段：结果与错误标记原样保留
            put(
                key,
                buckets.getValue(key).toMutableList().also { list ->
                    list[index] = Segment.Tool(
                        old.copy(
                            name = call.name.ifBlank { old.name },
                            arguments = call.arguments.ifBlank { old.arguments },
                        ),
                    )
                },
            )
            drainPendingResults()
            return segments()
        }
        val key = continuationKey(turn, step)
        put(key, buckets[key].orEmpty() + Segment.Tool(call))
        drainPendingResults()
        return segments()
    }

    /** `tool/result` → 按 callId 回填结果/错误标记（不新增重复段） */
    fun onToolResult(callId: String?, result: String?, isError: Boolean): List<Segment> {
        if (callId.isNullOrEmpty()) return segments()
        val hit = findTool(callId)
        if (hit == null) {
            // 结果比调用先到（tool/call 与权威 message 都还没来）：先记账，等调用登记后补
            pendingResults[callId] = result to isError
            return segments()
        }
        applyResult(hit, result, isError)
        return segments()
    }

    /** `assistant/chunk` 增量 → 该 step 桶的尾段（上游若恢复 delta 通知，流式观感照旧） */
    fun onChunkDelta(turn: Int?, step: Int?, chunkType: String?, text: String?): List<Segment> {
        if (text.isNullOrEmpty()) return segments()
        val key = continuationKey(turn, step)
        val bucket = buckets[key].orEmpty()
        val updated = when (chunkType) {
            DshChunkTypes.REASONING_DELTA -> appendReasoning(bucket, text)
            DshChunkTypes.TEXT_DELTA -> appendText(bucket, text)
            else -> bucket
        }
        if (updated !== bucket) put(key, updated)
        return segments()
    }

    /** 展开：真实 (turn, step) 升序 + 缺号桶按到达顺序，即事件日志的时间序 */
    fun segments(): List<Segment> = order.flatMap { buckets[it].orEmpty() }

    // ── 内部 ──

    /** 权威 message 的 key：缺 turn/step 时开一个新的缺号桶（追加在展开序末尾，不并进上一步） */
    private fun messageKey(turn: Int?, step: Int?): StepKey =
        if (turn != null && step != null) StepKey(turn, step) else StepKey(0, ++syntheticSeq, synthetic = true)

    /** 增量/工具事件的 key：缺 turn/step 时并进最近一个桶（同一 step 的延续事件） */
    private fun continuationKey(turn: Int?, step: Int?): StepKey = when {
        turn != null && step != null -> StepKey(turn, step)
        order.isNotEmpty() -> order.last()
        else -> StepKey(0, ++syntheticSeq, synthetic = true)
    }

    /** 取桶（不存在则按规则插入展开序并建空桶） */
    private fun bucket(key: StepKey): List<Segment> {
        buckets[key]?.let { return it }
        if (key.synthetic) {
            order += key
        } else {
            val at = order.indexOfFirst {
                !it.synthetic && (it.turn > key.turn || (it.turn == key.turn && it.step > key.step))
            }
            if (at < 0) order += key else order.add(at, key)
        }
        buckets[key] = emptyList()
        return emptyList()
    }

    private fun put(key: StepKey, list: List<Segment>) {
        bucket(key)
        buckets[key] = list
    }

    /** 把记着账的 `tool/result` 补到已登记的调用段上（幂等、补完即销账） */
    private fun drainPendingResults() {
        if (pendingResults.isEmpty()) return
        val it = pendingResults.entries.iterator()
        while (it.hasNext()) {
            val (callId, result) = it.next()
            val hit = findTool(callId) ?: continue
            applyResult(hit, result.first, result.second)
            it.remove()
        }
    }

    private fun applyResult(hit: Pair<StepKey, Int>, result: String?, isError: Boolean) {
        val (key, index) = hit
        val old = (buckets.getValue(key)[index] as Segment.Tool).call
        put(
            key,
            buckets.getValue(key).toMutableList().also { list ->
                list[index] = Segment.Tool(old.copy(result = result ?: old.result, isError = isError))
            },
        )
    }

    /** 找已登记的同一 callId 段（所在 step key + 下标）；空 id 一律不认为命中 */
    private fun findTool(callId: String): Pair<StepKey, Int>? {
        if (callId.isEmpty()) return null
        for (key in order) {
            val index = buckets[key].orEmpty().indexOfFirst { it is Segment.Tool && it.call.id == callId }
            if (index >= 0) return key to index
        }
        return null
    }
}
