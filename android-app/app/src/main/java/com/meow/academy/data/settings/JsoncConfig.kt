package com.meow.academy.data.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSONC 通用管道（docs/design-dynamic-config.md §8.1）。
 *
 * 所有「文本动态配置」（markdown / typography / resources 等）共用：
 * 1. [stripJsonc]：JSONC 文本 → 纯 JSON（剥注释，字符串内 `//` 与 `/* */` 不动）；
 * 2. [parseConfigJsonc]：纯 JSON → 原始 Map（剔除 `_` 前缀元数据）；
 * 3. [deepMerge]：深合并（对象递归；数组/标量整体替换；null 视为无覆盖）。
 *
 * 不引第三方依赖：剥注释后交给 Android 内置 `org.json` 按严格 JSON 规范解析。
 */
typealias JsoncConfigRaw = Map<String, Any?>

/**
 * 剥掉 JSONC 注释（`//` 行注释、`/* */` 块注释），返回纯 JSON 文本。
 *
 * - 字符串内的 `//`、`/* */` 原样保留（用引号状态机跳过字符串体）；
 * - 支持转义引号（`\"`）与转义反斜杠（`\\`）；
 * - 注释替换为换行，保持行号不漂移（解析报错信息更准确）。
 */
fun stripJsonc(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    var inString = false
    var escaped = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            sb.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
            i++
            continue
        }
        when {
            c == '"' -> {
                inString = true
                sb.append(c)
                i++
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                // 行注释：跳到行尾（不含换行），换行保留
                i += 2
                while (i < text.length && text[i] != '\n') i++
                if (i < text.length) {
                    sb.append('\n')
                    i++
                }
            }
            c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                // 块注释：跳到 `*/`
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i = if (i + 1 < text.length) i + 2 else text.length
                sb.append('\n')
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/**
 * JSONC 文本 → 原始配置 Map。
 *
 * - 先 [stripJsonc] 剥注释，再按标准 JSON 解析；
 * - 顶层必须是对象；
 * - `_` 前缀字段（如 `_editableCount`）一律视为元数据，**所有层级剔除**，不进入结果。
 */
fun parseConfigJsonc(text: String): JsoncConfigRaw {
    val root = JSONObject(stripJsonc(text))
    return root.toPlainMap()
}

/**
 * 深合并：以 [defaults] 为底，把 [overrides] 覆盖进去。
 *
 * 语义（docs/design-dynamic-config.md §4 / §8.1）：
 * - 对象递归合并；
 * - 数组 / 标量整体替换；
 * - `null` = 「回退默认」，与「没写」等价 → 保留默认值；
 * - 结果中不包含 `_` 前缀键（由 [parseConfigJsonc] 提前剔除）。
 */
fun deepMerge(defaults: Map<String, Any?>, overrides: Map<String, Any?>): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>(defaults)
    for ((key, value) in overrides) {
        result[key] = when {
            value == null -> defaults[key]
            value is Map<*, *> -> {
                val base = defaults[key]
                if (base is Map<*, *>) {
                    deepMerge(base.toStringMap(), value.toStringMap())
                } else {
                    value.toStringMap()
                }
            }
            else -> value
        }
    }
    return result
}

private fun Map<*, *>.toStringMap(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(size)
    for ((k, v) in this) out[k.toString()] = v
    return out
}

// ── org.json → 纯 Kotlin 值（Map / List / 基本类型 / null） ──────────────

private fun JSONObject.toPlainMap(): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    for (key in keys()) {
        if (key.startsWith("_")) continue // `_` 前缀 = 元数据，不进配置对象
        map[key] = toPlainValue(get(key))
    }
    return map
}

private fun JSONArray.toPlainList(): List<Any?> {
    val list = ArrayList<Any?>(length())
    for (i in 0 until length()) {
        list += toPlainValue(get(i))
    }
    return list
}

private fun toPlainValue(value: Any?): Any? = when (value) {
    is JSONObject -> value.toPlainMap()
    is JSONArray -> value.toPlainList()
    JSONObject.NULL -> null
    else -> value
}
