package com.meow.academy.ui.chat

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
    } else {
        segments + Segment.Reasoning(text)
    }
}

/** text-delta 追加到末尾 Text 段；末尾不是 Text（或空列表）则新建一段 */
fun appendText(segments: List<Segment>, text: String): List<Segment> {
    if (text.isEmpty()) return segments
    val last = segments.lastOrNull()
    return if (last is Segment.Text) {
        segments.dropLast(1) + Segment.Text(last.text + text)
    } else {
        segments + Segment.Text(text)
    }
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
                "reasoning" -> Segment.Reasoning(obj.str("text") ?: "")
                "text" -> Segment.Text(obj.str("text") ?: "")
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
