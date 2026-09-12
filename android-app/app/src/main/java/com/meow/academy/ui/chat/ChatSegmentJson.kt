package com.meow.academy.ui.chat

/**
 * 聊天步骤序列的 JSON 序列化 / 解析 + 增量追加纯函数。
 * - segmentsToJson：落库 segmentsJson 字段；
 * - parseSegments / parseToolCalls：DB 读取后渲染（旧消息兼容）；
 * - appendReasoning / appendText：流式 delta 追加到末尾同类型段。
 * 全部无 Android 依赖，可独立单测。
 */

import com.meow.academy.rpc.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 有序步骤序列 → JSON（落库 segmentsJson 字段） */
fun segmentsToJson(segments: List<Segment>): JsonArray = buildJsonArray {
    segments.forEach { seg ->
        when (seg) {
            is Segment.Reasoning -> add(buildJsonObject {
                put("type", "reasoning")
                put("text", seg.text)
            })
            is Segment.Text -> add(buildJsonObject {
                put("type", "text")
                put("text", seg.text)
            })
            is Segment.Tool -> add(buildJsonObject {
                put("type", "tool")
                put("id", seg.call.id)
                put("name", seg.call.name)
                put("arguments", seg.call.arguments)
                put("result", seg.call.result)
                put("isError", seg.call.isError)
            })
        }
    }
}

/** reasoning-delta 追加到末尾 Reasoning 段；末尾不是 Reasoning（或空列表）则新建一段 */
fun appendReasoning(segments: List<Segment>, text: String): List<Segment> {
    if (text.isEmpty()) return segments
    val last = segments.lastOrNull()
    return if (last is Segment.Reasoning) {
        segments.dropLast(1) + Segment.Reasoning(last.text + text)
    } else if (text.isBlank()) {
        // 没有 Reasoning 段时不要因为一个纯空白 delta 新建空段
        segments
    } else {
        segments + Segment.Reasoning(text)
    }
}

/** text-delta 追加到末尾 Text 段；末尾不是 Text（或空列表）则新建一段 */
fun appendText(segments: List<Segment>, text: String): List<Segment> {
    if (text.isEmpty()) return segments
    val last = segments.lastOrNull()
    return if (last is Segment.Text) {
        // 已有 Text 段：连空白一起追加（保留正文里的换行/分段）
        segments.dropLast(1) + Segment.Text(last.text + text)
    } else if (text.isBlank()) {
        // 没有 Text 段时不要因为一个纯空白 delta 新建空段（DSH 工具调用前常发 "\n\n" 占位）
        segments
    } else {
        segments + Segment.Text(text)
    }
}

/**
 * `assistant/message` 事件的 content 块 → 有序步骤序列（0.1.5 修复：回合最终内容的权威来源）。
 *
 * 上游 0.1.5-rc.2 基线的 agent loop 自驱动 LLM 流，**不再逐 delta 发 `assistant/chunk` 通知**
 * （见 ChatStreamingController 的说明与 plan-dsh-upgrade-0.1.5 §11）。App 此前只认
 * `assistant/chunk`，导致每个回合都渲染成空气泡（「（空回复）」）——聊天页无法对话。
 *
 * 块 vocab（`packages/llm/llm/src/types.ts`）：
 *  - `text` → [Segment.Text]（纯空白丢弃，与 [parseSegments] 口径一致）
 *  - `reasoning` → [Segment.Reasoning]
 *  - `tool-call` → [Segment.Tool]（结果稍后由 `tool/result` 事件回填）
 *  - 未知块类型（merge-extensible：`image` / `tool-result` 等）静默跳过。
 */
fun assistantMessageSegments(blocks: JsonArray?): List<Segment> {
    if (blocks == null) return emptyList()
    return blocks.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        when (obj.str("type")) {
            "text" -> obj.str("text")?.takeIf { it.isNotBlank() }?.let { Segment.Text(it) }
            "reasoning" -> obj.str("text")?.takeIf { it.isNotBlank() }?.let { Segment.Reasoning(it) }
            "tool-call" -> Segment.Tool(
                ToolCallInfo(
                    id = obj.str("id") ?: "",
                    name = obj.str("name") ?: "unknown",
                    arguments = obj.str("arguments") ?: "",
                )
            )
            else -> null
        }
    }
}

/**
 * 把 `assistant/message` 的权威分段（[incoming]）**upsert** 进流式累积的 [existing]（幂等、可重入）。
 *
 * 上游 0.1.5 起不再逐 delta 发 `assistant/chunk`——正文的唯一来源就是本事件的 content，
 * 但 App 仍要兼容「某天上游又发 delta」的情形，所以不能简单替换 `existing`：
 *
 *  - 逐块从 [cursor] 起找可对齐的段：同类型且**权威文本以已累积文本为前缀**（流式期间
 *    的段是权威段的前缀）→ **原地改写为权威文本**（部分流式文本被补全，不产生重复段）；
 *  - 没找到 → 按序**插入到 [cursor] 处**（补齐「一个 delta 都没收到」的空消息，也补齐中途漏的段）；
 *  - 不做删除：流式期间已展示过的段（用户已读的思考/正文）一律保留，
 *    与上游 surface 层「已落地的替换不该抹掉人类已读内容」同款立场。
 *
 * 顺序不变式：插入只发生在游标处（前缀对齐点），故不会打乱已有工具段的相对次序。
 *
 * ⚠️ **调用纪律（2026-09-12 真机回归的根因）**：游标每次调用都从 0 开始，所以本函数只对
 * **同一个 step** 的段列表成立——跨 step 复用会把后一步的正文插到最前面（表现为「工具调用卡
 * 被挤到当前轮最下面」）。回合级累积必须走 [TurnSegmentBuilder]（按 (turn, step) 分桶后再展开）。
 */
fun mergeAssistantMessage(existing: List<Segment>, incoming: List<Segment>): List<Segment> {
    if (incoming.isEmpty()) return existing
    val out = existing.toMutableList()
    // 游标 = 尚未被权威块对齐的最早段下标（不变量：它之前的段都已对齐）
    var cursor = 0
    incoming.forEach { block ->
        val aligned = indexOfAligned(out, cursor, block)
        if (aligned >= 0) {
            out[aligned] = rebase(out[aligned], block)
            cursor = aligned + 1
        } else {
            out.add(cursor, block)
            cursor++
        }
    }
    return out
}

/**
 * 对齐命中时的「以旧段为底、用权威块改写」：文本/思考取权威文本（更完整），
 * 工具段**保留已由 `tool/result` 事件回填的 result/isError**（权威 message 里没有结果）。
 */
private fun rebase(old: Segment, block: Segment): Segment = when {
    old is Segment.Tool && block is Segment.Tool -> block.copy(
        call = block.call.copy(result = old.call.result, isError = old.call.isError),
    )
    else -> block
}

/** 从 [from] 起找可与 [block] 对齐的后续段（-1 = 无，需插入） */
private fun indexOfAligned(segments: List<Segment>, from: Int, block: Segment): Int {
    for (i in from until segments.size) {
        if (alignable(segments[i], block)) return i
    }
    return -1
}

/**
 * 对齐判定（顺序敏感）：
 *  - 工具段：id 相等（`tool/result` 已回填的 result 不参与比较）；
 *  - 文本/思考段：同类型且**已累积文本是权威文本的前缀**——相等即幂等命中，
 *    严格前缀即流式半截文本被权威文本补全。
 */
private fun alignable(a: Segment, b: Segment): Boolean = when {
    a is Segment.Tool && b is Segment.Tool -> a.call.id == b.call.id
    a is Segment.Text && b is Segment.Text -> b.text.startsWith(a.text)
    a is Segment.Reasoning && b is Segment.Reasoning -> b.text.startsWith(a.text)
    else -> false
}

/** 解析 segmentsJson → 有序步骤序列；null 表示旧消息（无 segmentsJson，走兼容渲染） */
fun parseSegments(json: String?): List<Segment>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val arr = Json.parseToJsonElement(json) as? JsonArray
            ?: return null
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            when (obj.str("type")) {
                // 过滤纯空白段：DSH 可能存过 "\n\n" 占位 text，旧库读出来直接丢弃
                "reasoning" -> obj.str("text")?.takeIf { it.isNotBlank() }?.let { Segment.Reasoning(it) }
                "text" -> obj.str("text")?.takeIf { it.isNotBlank() }?.let { Segment.Text(it) }
                "tool" -> Segment.Tool(
                    ToolCallInfo(
                        id = obj.str("id") ?: "",
                        name = obj.str("name") ?: "unknown",
                        arguments = obj.str("arguments") ?: "",
                        result = obj.str("result") ?: "",
                        isError = obj.str("isError")?.toBoolean() ?: false,
                    )
                )
                else -> null
            }
        }
    }.getOrNull()
}

/** 解析旧消息 toolCallsJson → 工具调用列表 */
fun parseToolCalls(json: String): List<ToolCallInfo> {
    return runCatching {
        val arr = Json.parseToJsonElement(json) as? JsonArray
            ?: return emptyList()
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            ToolCallInfo(
                id = obj.str("id") ?: "",
                name = obj.str("name") ?: "unknown",
                arguments = obj.str("arguments") ?: "",
                result = obj.str("result") ?: "",
                isError = obj.str("isError")?.toBoolean() ?: false,
            )
        }
    }.getOrDefault(emptyList())
}
