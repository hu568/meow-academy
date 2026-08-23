package com.meow.academy.ui.chat

import kotlin.math.roundToLong

/**
 * 调用量数字格式化（口径对齐 DSH web StatsLine / message-chrome）。
 * 全部为纯函数，便于以后单测。
 */

/** 紧凑 token：517 / 12.2K / 517K / 1.2M（对齐 web formatTokens） */
internal fun formatTokens(n: Long): String = when {
    n < 1_000L -> n.toString()
    n < 1_000_000L -> formatOne(n / 1000.0) + "K"
    else -> formatOne(n / 1_000_000.0) + "M"
}

/** 紧凑时长：<60s → “1.9s”，否则 “6m54s”（对齐 formatDuration） */
internal fun formatDuration(ms: Long): String {
    val s = ms / 1000.0
    if (s < 60) return formatOne(s) + "s"
    val whole = s.roundToLong()
    return "${whole / 60}m${whole % 60}s"
}

/** tok/s：≥10 取整，否则一位小数（对齐 formatTokensPerSecond） */
internal fun formatTokensPerSecond(tps: Double): String =
    if (tps >= 10) tps.roundToLong().toString() else formatOne(tps)

/** 首 token 秒数：<10 一位小数，否则取整（对齐 formatLatencySeconds） */
internal fun formatLatencySeconds(ms: Long): String {
    val s = Math.max(0, ms) / 1000.0
    return if (s < 10) formatOne(s) else s.roundToLong().toString()
}

/** 回合用时中文：x秒 / x分yy秒（对齐 duration.seconds/minutes） */
internal fun formatRunDuration(ms: Long): String {
    val total = Math.max(0, ms) / 1000
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes > 0) "${minutes}分${seconds.toString().padStart(2, '0')}秒" else "${seconds}秒"
}

/** 缓存命中百分比（v1 简化版）：整数取整；非全命中却取整到 100 时显示 99.9 */
internal fun cacheHitPercent(uncached: Long, cacheRead: Long, cacheWrite: Long): String? {
    val denominator = uncached + cacheRead + cacheWrite
    if (denominator == 0L) return null
    val missed = uncached + cacheWrite
    if (missed == 0L) return "100"
    val pct = (cacheRead * 100 + denominator / 2) / denominator
    return if (pct >= 100L && missed > 0L) "99.9" else pct.toString()
}

/** 一位小数显示（整数去掉 .0）：12.0 → 12，12.3 → 12.3，0.0 → 0 */
private fun formatOne(v: Double): String {
    val rounded = Math.round(v * 10) / 10.0
    val s = rounded.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}