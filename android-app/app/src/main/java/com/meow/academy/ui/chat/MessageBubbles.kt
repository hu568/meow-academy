package com.meow.academy.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.meow.academy.ui.theme.LocalThemeExtras
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meow.academy.data.chat.MessageEntity
import com.meow.academy.data.chat.MessageRole
import com.meow.academy.data.chat.MessageStatus
import com.meow.academy.rpc.DshParams

/** 单条消息行：按角色分发到用户气泡 / 助手分段主体（问答卡交互参数透传，plan-standard-mode §5.6） */
@Composable
fun MessageRow(
    msg: MessageEntity,
    pendingQuestion: PendingQuestion? = null,
    interactiveQuestionCallId: String? = null,
    onAnswerQuestion: (String, List<DshParams.QuestionAnswer>) -> Unit = { _, _ -> },
    onCancelQuestion: (String) -> Unit = {},
) {
    when (msg.role) {
        MessageRole.USER -> UserBubble(msg.content)
        MessageRole.ASSISTANT -> {
            val segments = parseSegments(msg.segmentsJson)
            val copyText = assistantFinalMarkdown(segments, msg.content)
            Column(modifier = Modifier.fillMaxWidth()) {
                if (segments != null) {
                    AssistantBody(
                        segments = segments,
                        status = msg.status,
                        pendingQuestion = pendingQuestion,
                        interactiveQuestionCallId = interactiveQuestionCallId,
                        onAnswerQuestion = onAnswerQuestion,
                        onCancelQuestion = onCancelQuestion,
                    )
                } else {
                    // 旧消息兼容：segmentsJson 为空时回退 thinking + toolCallsJson
                    Column {
                        msg.thinking.takeIf { it.isNotBlank() }?.let { ThinkingCard(it) }
                        msg.toolCallsJson?.let { json ->
                            parseToolCalls(json).forEach { ToolCard(it) }
                        }
                        AssistantBubble(msg.content, msg.status)
                    }
                }
                // 每轮助手回复下方操作栏：复制最后返回的正文（Markdown 原文）
                if (copyText.isNotBlank()) {
                    AssistantCopyButton(copyText)
                }
            }
        }
    }
}

/**
 * 复制内容 = 本轮对话模型最后返回的正文（Markdown 原文，非渲染后的纯文本）：
 * 取 segments 中最后一个 Text 段 —— 最后一次工具调用结束后返回的正文；无工具时取唯一正文段。
 * 旧消息（segmentsJson 为 null）回退到整条 content 字段。
 */
fun assistantFinalMarkdown(segments: List<Segment>?, fallbackContent: String): String =
    segments?.filterIsInstance<Segment.Text>()?.lastOrNull()?.text
        ?.takeIf { it.isNotBlank() }
        ?: if (segments == null) fallbackContent else ""

/** 助手回复下方操作栏：复制按钮（小图标 + 文案）；重新生成 / 更多 后续再加 */
@Composable
fun AssistantCopyButton(copyText: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(copyText))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "复制",
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 工具名 → 内置 Material 图标：不同种类的工具使用不同图标，未知工具回退到通用工具箱图标 */
private fun toolIcon(name: String): ImageVector = when {
    name.contains("bash", ignoreCase = true) ||
        name.contains("terminal", ignoreCase = true) ||
        name.contains("shell", ignoreCase = true) -> Icons.Outlined.Terminal
    name.contains("rag", ignoreCase = true) ||
        name.contains("vector", ignoreCase = true) ||
        name.contains("knowledge", ignoreCase = true) ||
        name.contains("kb", ignoreCase = true) ||
        name.contains("book", ignoreCase = true) -> Icons.Outlined.MenuBook
    name.contains("web", ignoreCase = true) ||
        name.contains("search", ignoreCase = true) ||
        name.contains("google", ignoreCase = true) -> Icons.Outlined.Language
    name.contains("str_replace", ignoreCase = true) ||
        name.contains("edit", ignoreCase = true) ||
        name.contains("replace", ignoreCase = true) ||
        name.contains("patch", ignoreCase = true) -> Icons.Outlined.FindReplace
    name.contains("read", ignoreCase = true) ||
        name.contains("view", ignoreCase = true) ||
        name == "cat" -> Icons.Outlined.Article
    name.contains("todo", ignoreCase = true) ||
        name.contains("task", ignoreCase = true) ||
        name.contains("plan", ignoreCase = true) -> Icons.Outlined.Checklist
    name.contains("write", ignoreCase = true) ||
        name.contains("create", ignoreCase = true) ||
        name.contains("touch", ignoreCase = true) -> Icons.Outlined.EditNote
    name.contains("time", ignoreCase = true) ||
        name.contains("date", ignoreCase = true) ||
        name.contains("clock", ignoreCase = true) -> Icons.Outlined.AccessTime
    name.contains("ask", ignoreCase = true) ||
        name.contains("question", ignoreCase = true) ||
        name.contains("confirm", ignoreCase = true) -> Icons.Outlined.Quiz
    name.contains("agent", ignoreCase = true) ||
        name.contains("subagent", ignoreCase = true) ||
        name.contains("delegate", ignoreCase = true) -> Icons.Outlined.SmartToy
    name.contains("job", ignoreCase = true) ||
        name.contains("process", ignoreCase = true) ||
        name.contains("background", ignoreCase = true) -> Icons.Outlined.History
    else -> Icons.Outlined.Handyman
}

/**
 * 助手消息主体：组外思考/文本按到达顺序展示（思考是独立折叠卡、文本是气泡，均不并入工具组）；
 * 工具调用序列折叠成一组，组内若存在「运行中」的工具则自动展开（运行输出完延迟自动收起）。
 * 问答工具（ask_user_question / exit_plan_mode）组内分流到 [QuestionCard]（§5.6）。
 *
 * **折叠层次是刻意设计（勿改）**：只把「工具调用序列」这一段折叠成组——
 * - 首个工具调用**之前**的思考与正文（模型的首次思考 / 首次输出）留在组外常驻可见：
 *   首段思考单独成卡（且 [ThinkingCard] 的 `pinned` 规则让它完成后也不自动收起），
 *   首段正文是普通气泡、本就没有折叠态；
 * - 末个工具调用**之后**的思考与正文同样留在组外（干活后的结论不该被折进工具堆里）。
 * 即「首次思考与首次输出不自动折叠」是既有行为，加动效时不要顺手把它们并进 [ToolGroup]。
 */
@Composable
fun AssistantBody(
    segments: List<Segment>,
    status: MessageStatus,
    pendingQuestion: PendingQuestion? = null,
    interactiveQuestionCallId: String? = null,
    onAnswerQuestion: (String, List<DshParams.QuestionAnswer>) -> Unit = { _, _ -> },
    onCancelQuestion: (String) -> Unit = {},
) {
    val toolIndices = segments.indices.filter { segments[it] is Segment.Tool }
    // 流式回合进行中：只有「当前最后一段」算正在长（用于思考卡的运行中自动展开，§B1）
    val streaming = status == MessageStatus.STREAMING

    Column {
        if (toolIndices.isEmpty()) {
            // 无工具：思考折叠卡 + 文本气泡，按顺序
            segments.forEachIndexed { index, seg ->
                when (seg) {
                    is Segment.Reasoning -> ThinkingCard(
                        thinking = seg.text,
                        running = streaming && index == segments.lastIndex,
                        // 首次思考（消息首段）刻意保留展开、不自动收起（§B1/C 组约定）
                        pinned = index == 0,
                    )
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> Unit
                }
            }
        } else {
            val firstTool = toolIndices.first()
            val lastTool = toolIndices.last()
            // 工具组前（对提问的思考 / 干活前的回复，均不并入工具组）
            for (i in 0 until firstTool) {
                when (val seg = segments[i]) {
                    is Segment.Reasoning -> ThinkingCard(
                        thinking = seg.text,
                        running = streaming && i == segments.lastIndex,
                        pinned = i == 0,
                    )
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> Unit
                }
            }
            // 工具调用序列折叠成一组
            ToolGroup(
                segments = segments.subList(firstTool, lastTool + 1),
                // 组内思考卡要判断「是否正在长」：用消息内的绝对下标（组子列表的下标会丢基准）
                baseIndex = firstTool,
                runningSegmentIndex = if (streaming) segments.lastIndex else -1,
                status = status,
                pendingQuestion = pendingQuestion,
                interactiveQuestionCallId = interactiveQuestionCallId,
                onAnswerQuestion = onAnswerQuestion,
                onCancelQuestion = onCancelQuestion,
            )
            // 工具组后（干活完成后的思考 / 最终回复）
            for (i in lastTool + 1 until segments.size) {
                when (val seg = segments[i]) {
                    is Segment.Reasoning -> ThinkingCard(
                        thinking = seg.text,
                        running = streaming && i == segments.lastIndex,
                    )
                    is Segment.Text -> TextBubble(seg.text, status)
                    is Segment.Tool -> Unit
                }
            }
        }
        // 空态兜底（尚无任何内容：加载中 / 空回复）
        if (segments.isEmpty()) {
            AssistantBubble("", status)
        }
    }
}

/** 文本气泡（Text 段）：流式中也直接渲染 Markdown，实时看到标题/列表/代码块等格式 */
@Composable
fun TextBubble(text: String, status: MessageStatus) {
    // DSH 在工具调用前常先发一个仅含换行的空白 text-delta（如 "\n\n"）；
    // 不渲染空白 Text 段，否则会显示成空圆角框（思考过程和工具调用之间夹着的灰框）。
    if (text.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                // 半透明：聊天底图透出，配合全屏遮罩保持可读
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f))
                .padding(10.dp),
        ) {
            if (text.isNotBlank()) {
                MarkdownText(text, streaming = status == MessageStatus.STREAMING)
            } else {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** 工具调用折叠组：收起时显示「工具箱图标 + 工具调用 xN」；有运行中的工具时自动展开，
 * 全部完成后再停顿 [AUTO_COLLAPSE_DELAY_MS] 才收起（不再是「一 done 就塌」，§B2）；
 * 用户点过则全手动、不再自动收起。问答工具分流到 QuestionCard（默认展开、配色区分，§5.6），
 * 其余走通用 ToolCard。
 *
 * 组内的思考段同样是**可折叠**的思考卡（2026-09-11 主人反馈：工具调用之间的思考原先只能整块显示、
 * 没法收起来）——用 [ThinkingCard] 而非死板的 ThinkingBlock，默认收起、可逐个点开，
 * 「正在长的那段」仍会自动展开。
 *
 * @param baseIndex 本组首段在整条消息里的绝对下标（算 [runningSegmentIndex] 用）
 * @param runningSegmentIndex 正流式生长的段下标（-1 = 无）
 */
@Composable
fun ToolGroup(
    segments: List<Segment>,
    status: MessageStatus,
    pendingQuestion: PendingQuestion? = null,
    interactiveQuestionCallId: String? = null,
    onAnswerQuestion: (String, List<DshParams.QuestionAnswer>) -> Unit = { _, _ -> },
    onCancelQuestion: (String) -> Unit = {},
    baseIndex: Int = 0,
    runningSegmentIndex: Int = -1,
) {
    val toolCount = segments.count { it is Segment.Tool }
    val hasRunning = segments.any {
        it is Segment.Tool && it.call.result.isBlank() && !it.call.isError
    }
    val collapse = rememberAutoCollapse(running = hasRunning)
    val anchor = rememberCollapseAnchor()

    val extras = LocalThemeExtras.current

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(extras.toolGroupBackground ?: MaterialTheme.colorScheme.secondaryContainer)
                .clickable {
                    collapse.toggle()
                    anchor.beginManualWindow()
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Handyman,
                contentDescription = "工具调用",
                modifier = Modifier.size(18.dp),
                tint = extras.toolGroupContent ?: MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "工具调用 x" + toolCount,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (collapse.expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            CollapseChevron(collapse.expanded)
        }
        // 组正文整体参与折叠动画；收起过程中子卡片仍在组合里（ToolCard 的展开态保留，回来还是原来的样子）
        CollapseBody(visible = collapse.expanded, anchor = anchor) {
            Column {
                segments.forEachIndexed { index, seg ->
                    when (seg) {
                        is Segment.Reasoning -> ThinkingCard(
                            thinking = seg.text,
                            running = baseIndex + index == runningSegmentIndex,
                        )
                        is Segment.Text -> TextBubble(seg.text, status)
                        is Segment.Tool ->
                            if (seg.call.name in QuestionToolNames) {
                                // 问答卡：需要用户操作的提问/计划审阅（§5.6）
                                QuestionCard(
                                    call = seg.call,
                                    pendingQuestion = pendingQuestion,
                                    interactive = interactiveQuestionCallId == seg.call.id,
                                    onAnswer = onAnswerQuestion,
                                    onCancel = onCancelQuestion,
                                )
                            } else {
                                ToolCard(seg.call)
                            }
                    }
                }
            }
        }
    }
}

/** 用户消息气泡（右侧，主色容器）：走 Markdown 渲染，上传附件引用会显示成可点击链接 */
@Composable
fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                // 半透明：聊天底图透出，配合全屏遮罩保持可读
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f))
                .padding(12.dp),
        ) {
            if (text.isNotBlank()) {
                MarkdownText(
                    text,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                SelectionContainer {
                    Text("", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** 助手整条消息气泡（兼容旧数据：纯 content 渲染） */
@Composable
fun AssistantBubble(content: String, status: MessageStatus) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                // 半透明：聊天底图透出，配合全屏遮罩保持可读
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f))
                .padding(10.dp),
        ) {
            when {
                content.isBlank() && status == MessageStatus.STREAMING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("思考中…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                content.isNotBlank() -> MarkdownText(
                    content,
                    streaming = status == MessageStatus.STREAMING,
                )
                else -> Text(
                    status.takeIf { it == MessageStatus.ERROR }?.let { "⚠️ 出错" } ?: "（空回复）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * thinking 折叠胶囊：默认收起一行、点击展开。
 *
 * @param running 该思考段是否正在生长（流式回合的最后一段）：运行中自动展开，完成后再停顿一拍收起
 * @param pinned 是否「模型首次思考」（消息首段）：默认展开且**永不自动收起**（§B1/C 组约定，
 *   首段思考是用户最先要读的内容；用户手动收起后仍以用户为准）
 */
@Composable
fun ThinkingCard(thinking: String, running: Boolean = false, pinned: Boolean = false) {
    val collapse = rememberAutoCollapse(running = running, pinned = pinned)
    val anchor = rememberCollapseAnchor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    collapse.toggle()
                    anchor.beginManualWindow()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Science,
                contentDescription = "思考过程",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                // 思考在长：给一个进度点，和「已完成」的静默形成区分
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            CollapseChevron(collapse.expanded)
        }
        CollapseBody(visible = collapse.expanded, anchor = anchor) {
            SelectionContainer {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 工具调用胶囊：默认折叠成一行（图标 + 工具名 + 状态 + 箭头），点击展开参数/结果（展开/收起带动画，§B3） */
@Composable
fun ToolCard(tool: ToolCallInfo) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    val anchor = rememberCollapseAnchor()
    val extras = LocalThemeExtras.current
    val statusMark = when {
        tool.isError -> "✗"
        tool.result.isNotBlank() -> "✓"
        else -> "…"
    }
    val statusColor = when {
        tool.isError -> MaterialTheme.colorScheme.error
        tool.result.isNotBlank() -> extras.toolStatusColor ?: MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor = if (tool.isError) MaterialTheme.colorScheme.errorContainer
                         else MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    anchor.beginManualWindow()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                toolIcon(tool.name),
                contentDescription = tool.name,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                tool.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(statusMark, color = statusColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            CollapseChevron(expanded)
        }
        CollapseBody(visible = expanded, anchor = anchor) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                if (tool.arguments.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            "参数：" + tool.arguments,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (tool.result.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            "结果：" + tool.result.take(2000),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (tool.isError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else if (!tool.isError) {
                    Text(
                        "执行中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
