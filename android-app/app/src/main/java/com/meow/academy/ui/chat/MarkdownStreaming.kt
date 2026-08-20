package com.meow.academy.ui.chat

/**
 * 流式 Markdown 块拆分器（半增量渲染的纯函数部分，无 Android 依赖，可单测）。
 *
 * 主流流式渲染方案（semidown / MarkdownDisplayView）采用「块级增量 + 块内行内重渲染」：
 * - 已完成（稳定）的块只解析渲染一次并缓存；
 * - 正在增长的最后一个块（活动块）每次刷新重渲染。
 *
 * 本拆分器把流式中的 Markdown 文本拆成 [StreamingBlocks.stable]（稳定块）与
 * [StreamingBlocks.active]（活动块）：
 * - 围栏代码块（``` / ~~~）与 LaTeX 块（$$ / $$$）只有闭合后才算稳定；
 * - GFM 表格从「表头 + 分隔行」起算一个块，连续含 | 的行持续追加，遇到非 | 行结束；
 * - 空行结束当前块；其余连续非空行合并为一个「文本运行」块（段落/标题/列表/引用交给 Markwon）。
 */

data class StreamingBlocks(
    /** 已完成、不会再变化的块（按原始顺序，不含块间空行） */
    val stable: List<String>,
    /** 正在增长的最后一块；流式未结束时为空串表示刚结束一个块、新块还没内容 */
    val active: String,
)

/** 把流式 Markdown 全文拆成稳定块 + 活动块 */
fun splitStreamingBlocks(markdown: String): StreamingBlocks {
    val lines = markdown.split("\n").map { it.removeSuffix("\r") }
    val stable = ArrayList<String>()
    var current = ArrayList<String>()
    var fenceChar: Char? = null
    var fenceLen = 0
    var currentIsTable = false

    fun flush() {
        if (current.isNotEmpty()) stable += current.joinToString("\n")
        current = ArrayList()
        currentIsTable = false
    }

    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trim()
        val isBlank = trimmed.isEmpty()

        // ① 围栏内部：一直追加，直到匹配的闭栏行；闭合的围栏块立即稳定
        if (fenceChar != null) {
            current += line
            if (isFenceClose(trimmed, fenceChar!!, fenceLen)) {
                fenceChar = null
                fenceLen = 0
                flush()
            }
            continue
        }

        // ② 开栏检测：``` / ~~~（允许信息串）/ $$ 块（开栏行必须只有 $）
        val open = detectFenceOpen(trimmed)
        if (open != null) {
            flush()
            current += line
            fenceChar = open.first
            fenceLen = open.second
            continue
        }

        // ③ 空行：结束当前块（流式以空行收尾时活动块为空串）
        if (isBlank) {
            flush()
            continue
        }

        // ④ 表格块延续：分隔行与后续含 | 的行都追加；非 | 行结束表格
        if (currentIsTable) {
            if (line.contains("|")) {
                current += line
                continue
            }
            flush()
            // 本行作为新块首行继续处理
        }

        // ⑤ 表格块开启：本行含 | 且下一行是分隔行
        if (line.contains("|") && i + 1 < lines.size && isTableDelimiter(lines[i + 1])) {
            flush()
            current += line
            currentIsTable = true
            continue
        }

        // ⑥ 普通文本运行行
        current += line
    }

    return StreamingBlocks(
        stable = stable,
        active = current.joinToString("\n"),
    )
}

/** 开栏检测：``` / ~~~ 长度 >= 3；$$ 块要求整行只有 $（$ 数量 >= 2），避免误吞行内公式 */
private fun detectFenceOpen(trimmed: String): Pair<Char, Int>? {
    if (trimmed.isEmpty()) return null
    val first = trimmed[0]
    val run = trimmed.takeWhile { it == first }.length
    val isFence = when (first) {
        '`', '~' -> run >= 3
        '$' -> run >= 2 && trimmed.drop(run).isBlank()
        else -> false
    }
    return if (isFence) first to run else null
}

/** 闭栏检测：同一字符、$$ 要求数量一致、围栏要求 >= 开栏数量，且行内剩余只能空白 */
private fun isFenceClose(trimmed: String, fenceChar: Char, fenceLen: Int): Boolean {
    if (trimmed.isEmpty() || trimmed[0] != fenceChar) return false
    val run = trimmed.takeWhile { it == fenceChar }.length
    if (!trimmed.drop(run).isBlank()) return false
    return if (fenceChar == '$') run == fenceLen else run >= fenceLen
}

/** 判断一行是否为 GFM 表格分隔行：`| --- | :---: | ---: |` 等 */
fun isTableDelimiter(line: String): Boolean {
    val trimmed = line.removeSuffix("\r").trim()
    if (trimmed.isEmpty()) return false
    val body = trimmed.removePrefix("|").removeSuffix("|").trim()
    if (body.isEmpty()) return false
    return body.split("|").all { cell ->
        val t = cell.trim()
        t.isNotEmpty() && t.all { it == '-' || it == ':' || it == ' ' }
    }
}
