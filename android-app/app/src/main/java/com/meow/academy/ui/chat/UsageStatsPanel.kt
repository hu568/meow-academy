package com.meow.academy.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.SessionLastStep
import com.meow.academy.data.chat.SessionUsageStats
import com.meow.academy.ui.components.EmptyStateCompact
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 右侧看板「调用量」：当前会话模型的调用量 dashboard。
 * 卡片风大数字（轮/步）、LLM/工具时长、首 token/tok/s、缓存命中环、上下文使用量环、
 * token 用量比例条、最新回合强调卡；不引入第三方图表库。
 */
@Composable
fun UsageStatsPanel(
    stats: SessionUsageStats?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    if (stats == null && !loading) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyStateCompact(icon = Icons.Outlined.Insights, title = "暂无调用量")
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
        return
    }
    val s = stats
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① Hero 大数卡：渐变底 + 大号「X 轮 · Y 步」
        val heroBrush = Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
        )
        Card(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(heroBrush)
                    .padding(20.dp),
            ) {
                Column {
                    Text(
                        "${s.turns} 轮 · ${s.steps} 步",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        "当前会话调用量",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    )
                }
            }
        }

        // ② 时长双卡
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Schedule,
                label = "LLM",
                value = formatDuration(s.llmMs),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Build,
                label = "工具调用",
                value = formatDuration(s.toolMs),
            )
        }

        // ③ 性能双卡
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val ttftAvg = if (s.ttftSteps > 0) s.ttftMs.toDouble() / s.ttftSteps else 0.0
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Bolt,
                label = "首 token 平均",
                value = formatDuration(ttftAvg.roundToLong()),
            )
            val tps = if (s.decodeMs > 0) s.decodeTokens.toDouble() / (s.decodeMs / 1000.0) else 0.0
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Speed,
                label = "tok/s",
                value = formatTokensPerSecond(tps),
            )
        }

        // ④ 双环：缓存命中 + 上下文使用量（context 缺失时缓存环独占一行）
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cacheHit = cacheHitPercent(s.inputTokens, s.cacheReadTokens, s.cacheWriteTokens)
            RingCard(
                modifier = Modifier.weight(1f),
                percentText = cacheHit ?: "—",
                label = "缓存命中",
                progress = cacheHit?.toFloatOrNull()?.div(100f) ?: 0f,
            )
            if (s.context != null) {
                val used = s.context.usedTokens.toFloat()
                val window = s.context.contextWindow.toFloat()
                val pct = if (window > 0) (used / window).coerceIn(0f, 1f) else 0f
                RingCard(
                    modifier = Modifier.weight(1f),
                    percentText = "${(pct * 100).roundToInt()}%",
                    label = "上下文使用量",
                    progress = pct,
                    sub = "${formatTokens(s.context.usedTokens)} / ${formatTokens(s.context.contextWindow)}",
                )
            }
        }

        // ⑤ Token 用量比例条
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Token 用量", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val total = s.inputTokens + s.outputTokens
                val inputFraction = if (total > 0) s.inputTokens.toFloat() / total.toFloat() else 0f
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("输入 ${formatTokens(s.inputTokens)} tok", style = MaterialTheme.typography.bodySmall)
                    Text("输出 ${formatTokens(s.outputTokens)} tok", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { inputFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
            }
        }

        // ⑥ 最新回合强调卡
        if (s.lastStep != null) {
            LatestTurnCard(s.lastStep)
        }
    }
}

/** 最新回合强调卡：用时 · 首 token · tok/s */
@Composable
private fun LatestTurnCard(step: SessionLastStep) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "最新回合",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            val tps = if (step.decodeMs > 0)
                step.decodeTokens.toDouble() / (step.decodeMs / 1000.0)
            else 0.0
            Text(
                "用时 ${formatRunDuration(step.llmMs)} · 首 token ${formatLatencySeconds(step.ttftMs)}秒 · ${formatTokensPerSecond(tps)} tok/s",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** 通用小指标卡：图标 + 标签 + 大数字 */
@Composable
private fun StatCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 环形指标卡：Canvas drawArc 圆环 + 中心百分比 + 标签/副文字 */
@Composable
private fun RingCard(
    modifier: Modifier,
    percentText: String,
    label: String,
    progress: Float,
    sub: String? = null,
) {
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f))
    Card(modifier) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            val progressColor = MaterialTheme.colorScheme.tertiary
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 7.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke),
                    )
                    if (animatedProgress > 0f) {
                        drawArc(
                            color = progressColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Text(percentText, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}