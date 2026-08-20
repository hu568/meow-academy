package com.meow.academy.ui.chat

/**
 * 单 `$…$` 行内公式匹配（纯函数，无 Android 依赖，可单测）。
 *
 * 匹配规则偏保守，避免误伤货币等普通文本：
 * - 开/闭 $ 都不能与相邻 $ 相连（`$$…$$` 不是单美元公式）；
 * - $ 后、$ 前都不能是空白（`$ x$` 不匹配）；
 * - 内容里不含 $ 与换行；
 * - 内容至少一个非空白字符（`$x$` 可匹配，`$$` 空内容不匹配）。
 * 因此 `$5 to $10`（闭 $ 前是空格）不会被当成公式。
 *
 * @param s 原文
 * @param start 开 $ 的索引
 * @return 公式内容（不含两侧 $）；不是合法单美元公式时返回 null
 */
fun matchDollarMath(s: String, start: Int): String? {
    if (start < 0 || start >= s.length || s[start] != '$') return null

    // 与相邻 $ 相连 → 不是单美元公式（$$…$$ 交给内建处理器）
    if (start + 1 < s.length && s[start + 1] == '$') return null
    if (start - 1 >= 0 && s[start - 1] == '$') return null

    // 内容首字符不能是空白
    val first = start + 1
    if (first >= s.length || s[first].isWhitespace()) return null

    // 找闭 $：内容不含 $ 与换行
    var j = first
    while (j < s.length && s[j] != '$' && s[j] != '\n') j++
    if (j >= s.length || s[j] != '$') return null

    // 内容尾字符不能是空白；闭 $ 不能与下一个 $ 相连
    if (j - 1 < first || s[j - 1].isWhitespace()) return null
    if (j + 1 < s.length && s[j + 1] == '$') return null

    return s.substring(first, j)
}
