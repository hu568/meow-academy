package com.meow.academy.ui.chat

/**
 * 聊天页消息列表分片（plan-chatscreen-refactor §2.1）：
 * 自管快照 / 滚动跟随 / 左滑手势 / 问答卡推导 / 回底 FAB / 悬浮栏，不再读薄壳闭包状态。
 * 唯一外部输入 = 显式参数；listState 由薄壳持有传入（单根，防回底失效）。
 */

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.rpc.DshParams
import com.meow.academy.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * 消息列表区：空状态 / LazyColumn / 流式气泡 / 回底 FAB / 悬浮栏 + 快照 + 问答卡推导 + 左滑手势。
 * @param currentId 用于问答卡 remember key（切会话重算，保持原语义）
 * @param dshSessionIdOf 分片内部算 pendingQuestionForSession（原始待答状态按会话过滤）
 */
@Composable
fun ChatMessageList(
    modifier: Modifier = Modifier,             // 薄壳传 Modifier.fillMaxSize().padding(padding)
    messages: List<MessageEntity>,
    streaming: StreamingState?,
    currentId: Long?,
    pendingQuestion: PendingQuestion?,
    dshSessionIdOf: (Long?) -> String,
    onAnswerQuestion: (String, List<DshParams.QuestionAnswer>) -> Unit,
    onCancelQuestion: (String) -> Unit,
    todos: List<TodoItemView>?,
    subagentRuns: List<SubagentRun>,
    onOpenDashboard: () -> Unit,               // 左滑手势触发
    listState: LazyListState,                  // 薄壳持有传入（红线：单根，防回底失效）
) {
    val scope = rememberCoroutineScope()

    // ── 脱离自动滚动（Chatbox 风格）──
    // reverseLayout 下 index 0 = 屏幕底部。贴底时列表天然跟随新内容（流式增长/新消息），
    // 不需要也不应该每 token 调 scrollToItem（否则高频抽搐/文字重叠）；
    // 用户上滑离开底部即脱离跟随，滑回底部即恢复跟随。
    val isAtBottom by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // ── 流式气泡冻结快照 ──
    // 贴底跟随：实时渲染最新 segments，并同步刷新快照；
    // 上滑脱离：渲染冻结快照（离开底部那一刻的内容），流式在后台继续增长但不参与布局。
    // 这样看历史时气泡高度不变，LazyColumn 不会因流式气泡长高而把历史内容顶得跳动。
    val snapshotHolder = remember(streaming?.messageId) {
        StreamingSegmentsSnapshot().also { it.segments = streaming?.segments }
    }
    val currentStreaming = streaming
    if (isAtBottom && currentStreaming != null) {
        snapshotHolder.segments = currentStreaming.segments
    }
    val displayedStreamingSegments = if (isAtBottom) currentStreaming?.segments else snapshotHolder.segments

    // ── 问答卡交互绑定（§5.6）──
    // 当前会话最新一个「未回答」的问答卡 call.id（消息流 + 流式一起找最后一个；result 非空 = 已答）；
    // pendingQuestion 属于当前会话时才启用交互。
    val latestQuestionCallId = remember(messages, streaming, currentId) {
        sequence {
            messages.forEach { m ->
                parseSegments(m.segmentsJson)?.forEach { seg ->
                    if (seg is Segment.Tool && seg.call.name in QuestionToolNames && seg.call.result.isBlank()) {
                        yield(seg.call.id)
                    }
                }
            }
            streaming?.segments?.forEach { seg ->
                if (seg is Segment.Tool && seg.call.name in QuestionToolNames && seg.call.result.isBlank()) {
                    yield(seg.call.id)
                }
            }
        }.lastOrNull()
    }
    val pendingQuestionForSession = pendingQuestion?.takeIf { pq ->
        pq.sessionId == null || pq.sessionId == dshSessionIdOf(currentId)
    }

    Column(
        modifier = modifier
            // 和左抽屉一致：在聊天内容区任意位置向左滑即可打开功能看板。
            // 注意这里必须用“只观察不消费”的手势，否则会抢走左侧 ModalNavigationDrawer 的滑动手势。
            .swipeToOpenDashboard(onOpenDashboard),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && streaming == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "和喵喵老师聊聊吧～",
                        description = "左上角管理会话 · 右上角新建",
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true,
                ) {
                    // 过滤掉正在流式的 DB 行（节流落库会产生部分内容），避免与实时气泡同屏重复渲染
                    val visible = messages.filterNot { it.id == streaming?.messageId }
                    // reverseLayout 下 index 0 在屏幕底部：
                    // 先放实时流式气泡（新内容），再放历史消息的倒序（越旧越往上）。
                    streaming?.let { s ->
                        item(key = "streaming-${s.messageId}") {
                            AssistantBody(
                                segments = displayedStreamingSegments ?: s.segments,
                                status = MessageStatus.STREAMING,
                                pendingQuestion = pendingQuestionForSession,
                                interactiveQuestionCallId = latestQuestionCallId,
                                onAnswerQuestion = onAnswerQuestion,
                                onCancelQuestion = onCancelQuestion,
                            )
                        }
                    }
                    items(visible.asReversed(), key = { it.id }) { msg ->
                        MessageRow(
                            msg = msg,
                            pendingQuestion = pendingQuestionForSession,
                            interactiveQuestionCallId = latestQuestionCallId,
                            onAnswerQuestion = onAnswerQuestion,
                            onCancelQuestion = onCancelQuestion,
                        )
                    }
                }
            }
            // 上滑脱离跟随后出现「回到底部」：点击回到最新内容并恢复跟随
            if (!isAtBottom) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // 上方悬浮栏（§5.5）：浮在消息列表上层（消息从面板下方透过），
            // todo / subagent 两态；两态都无数据 → 整条不渲染
            ChatStatusBar(
                todos = todos,
                subagentRuns = subagentRuns,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * 左滑打开右侧功能看板的手势扩展：挂在聊天内容区（顶栏与输入栏之间）。
 * 只观察不消费（requireUnconsumed = false），不抢左侧 ModalNavigationDrawer 的滑动手势；
 * pointerInput(Unit) key 固定，不随状态重启手势。
 */
private fun Modifier.swipeToOpenDashboard(onOpenDashboard: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var opened = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                totalX += change.positionChange().x
                if (!opened && totalX <= -viewConfiguration.touchSlop) {
                    opened = true
                    onOpenDashboard()
                }
            }
        }
    }

/** 非快照状态的持有者：组合期间同步记录「贴底时的最新流式分段」，避免写 State 引发额外重组 */
private class StreamingSegmentsSnapshot {
    var segments: List<Segment>? = null
}
