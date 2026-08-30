package com.meow.academy.ui.chat

/**
 * 问答卡（plan-standard-mode §5.6）：`ask_user_question` / `exit_plan_mode` 工具调用渲染为
 * 专用问答卡片（工具卡外观、可折叠、默认展开、primaryContainer 底 + 高亮描边与普通工具卡反差）。
 *
 * 渲染分层（主人澄清：约束是「不改渲染管线」而非「不用渲染组件」）：
 * - 卡片骨架 / 折叠 / 配色 / 选项按钮 / 文本框 = 原生 Compose；
 * - 正文（计划 Markdown、问题文本）复用聊天气泡同款 [MarkdownText]，纯只读复用、零管线改动
 *   （不往 Markdown 管线加问答专用块/插件、不动 markdown-config）；
 * - 选项 label / description 等短文案保持原生 Text。
 *
 * 交互绑定：[pendingQuestion] 非空（session.question 通知在）且本卡是会话最新一个未回答的
 * 问答卡（interactive）时选项可操作；提交 → onAnswer（回答按原文 label 回传）；
 * 「先不打扰」→ onCancel。tool/result 到达（Segment.Tool.result 非空）→ 自动转已答折叠态。
 * 不新增 segment 类型；历史会话旧问答卡（result 已有值）回退通用工具卡也可以——本卡直接渲染
 * 已答折叠态同样成立。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meow.academy.rpc.DshParams
import com.meow.academy.rpc.bool
import com.meow.academy.rpc.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 问答卡工具名集合（Segment.Tool.name ∈ 此集合 → QuestionCard，不走通用工具卡） */
internal val QuestionToolNames = setOf("ask_user_question", "exit_plan_mode")

/** plan 审阅问题的缺省 id（plan-mode 侧 REVIEW_ID；以通知下发为准，缺省兜底） */
private const val PLAN_REVIEW_ID = "plan-review"

/** plan 审阅上游缺省 label（DSH plan-mode 常量；回答按原文 label 回传，缺省时兜底） */
private const val DEFAULT_APPROVE_LABEL = "Approve"
private const val DEFAULT_KEEP_PLANNING_LABEL = "Keep planning"

/**
 * ask_user_question 的 questions[] 解析（工具参数持久化在 segmentsJson，resume 后仍可渲染）。
 * 工具参数是 `multi_select`（下划线），session.question 通知是 `multiSelect`（驼峰），两者都兼容。
 */
internal fun parseQuestionItemsFromArgs(arguments: String): List<QuestionItem> = runCatching {
    val obj = Json.parseToJsonElement(arguments) as? JsonObject ?: return emptyList()
    val arr = obj["questions"] as? JsonArray ?: return emptyList()
    arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val options = (o["options"] as? JsonArray)?.mapNotNull { oe ->
            (oe as? JsonObject)?.let { OptionItem(label = it.str("label") ?: "", description = it.str("description")) }
        }.orEmpty()
        QuestionItem(
            id = id,
            question = o.str("question") ?: "",
            detail = o.str("detail"),
            header = o.str("header"),
            options = options,
            multiSelect = o.bool("multi_select") == true || o.bool("multiSelect") == true,
        )
    }
}.getOrDefault(emptyList())

/** exit_plan_mode 的计划正文（参数 {plan}，本就要求 # 开头的 Markdown） */
internal fun planMarkdownFromArgs(arguments: String): String? = runCatching {
    (Json.parseToJsonElement(arguments) as? JsonObject)?.str("plan")?.takeIf { it.isNotBlank() }
}.getOrNull()

/** 已答摘要（result/isError 到达后卡片头部显示所选/回答） */
internal fun questionAnswerSummary(call: ToolCallInfo, questions: List<QuestionItem>): String? {
    if (call.result.isBlank() && !call.isError) return null
    if (call.name == "exit_plan_mode") {
        return when {
            call.isError -> "继续规划（用户想直接说话喵）"
            call.result.contains("approved") -> "已批准，开始执行喵~"
            else -> "已处理"
        }
    }
    return runCatching {
        val obj = Json.parseToJsonElement(call.result) as? JsonObject ?: return@runCatching null
        val arr = obj["answers"] as? JsonArray ?: return@runCatching null
        val byId = arr.mapNotNull { el -> el as? JsonObject }.associateBy { it.str("id") }
        questions.mapNotNull { q ->
            val answer = byId[q.id] ?: return@mapNotNull null
            val selected = (answer["selected"] as? JsonArray)
                ?.mapNotNull { it as? JsonPrimitive }
                ?.mapNotNull { it.contentOrNull }
                .orEmpty()
                .filter { it.isNotBlank() }
            val custom = answer.str("custom")?.takeIf { it.isNotBlank() }
            val head = q.header ?: q.question.take(12)
            when {
                custom != null -> "$head：$custom"
                selected.isNotEmpty() -> "$head：" + selected.joinToString("、")
                else -> null
            }
        }.joinToString("；").ifBlank { null }
    }.getOrNull()
}

/** 问答卡本体（在消息流的工具组内渲染；配色与交互规则见文件头注释） */
@Composable
fun QuestionCard(
    call: ToolCallInfo,
    pendingQuestion: PendingQuestion?,
    interactive: Boolean,
    onAnswer: (String, List<DshParams.QuestionAnswer>) -> Unit,
    onCancel: (String) -> Unit,
) {
    val isPlanReview = call.name == "exit_plan_mode"
    val questions = remember(call.id, call.arguments) { parseQuestionItemsFromArgs(call.arguments) }
    val planMarkdown = remember(call.id, call.arguments) { planMarkdownFromArgs(call.arguments) }
    val answered = call.result.isNotBlank() || call.isError
    val summary = remember(call.id, call.result, call.isError) { questionAnswerSummary(call, questions) }

    // 默认展开；tool/result 到达 → 自动转已答折叠态
    var expanded by remember(call.id) { mutableStateOf(true) }
    LaunchedEffect(answered) { if (answered) expanded = false }

    // 交互状态（不可变 + copy，重组才生效喵）：每题勾选项 / 是否用自由文本 / 自由文本内容
    var selections by remember(call.id) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var useCustom by remember(call.id) { mutableStateOf<Set<String>>(emptySet()) }
    var customTexts by remember(call.id) { mutableStateOf<Map<String, String>>(emptyMap()) }

    // plan 审阅按钮：UI 显示中文，回答按原文 label 回传（以通知 intent.approve / options 为准，缺省常量）
    val planQuestion = pendingQuestion?.questions?.firstOrNull { it.intentKind == "plan-review" }
    val approveLabel = planQuestion?.intentApprove
        ?: planQuestion?.options?.getOrNull(0)?.label
        ?: DEFAULT_APPROVE_LABEL
    val keepLabel = planQuestion?.options?.getOrNull(1)?.label ?: DEFAULT_KEEP_PLANNING_LABEL

    val containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val canInteract = interactive && !answered && pendingQuestion != null

    // 提交可用性：每题要么勾了选项、要么在「其他」里填了文本（plan 审阅恒可提交）
    val submitReady = if (isPlanReview) {
        true
    } else {
        questions.isNotEmpty() && questions.all { q ->
            (q.id in useCustom && !customTexts[q.id].isNullOrBlank()) ||
                selections[q.id]?.isNotEmpty() == true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // 标题行：图标 + 标题（已答时带摘要）+ 折叠箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isPlanReview) Icons.Outlined.Checklist else Icons.Outlined.Quiz,
                contentDescription = call.name,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPlanReview) "计划审阅" else "需要你的回答",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                if (answered && summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(if (expanded) "▾" else "▸", color = contentColor)
        }

        if (expanded) {
            if (isPlanReview) {
                // 计划正文：Markdown 渲染（可滚动、限高 ~240dp）
                val plan = planMarkdown
                    ?: planQuestion?.detail
                    ?: "（模型未提供计划正文喵…）"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(plan, textColor = contentColor)
                }
            } else {
                questions.forEach { q ->
                    QuestionBlock(
                        question = q,
                        contentColor = contentColor,
                        interactive = canInteract,
                        selected = selections[q.id].orEmpty(),
                        useCustom = q.id in useCustom,
                        customText = customTexts[q.id].orEmpty(),
                        onToggleOption = { label ->
                            val current = selections[q.id].orEmpty()
                            selections = if (q.multiSelect) {
                                // 多选：勾选集合增减
                                selections + (q.id to if (label in current) current - label else current + label)
                            } else {
                                // 单选：点击已选 = 取消，点其他 = 换选
                                if (label in current) selections + (q.id to emptySet())
                                else selections + (q.id to setOf(label))
                            }
                        },
                        onToggleCustom = {
                            useCustom = if (q.id in useCustom) useCustom - q.id else useCustom + q.id
                        },
                        onCustomChange = { text -> customTexts = customTexts + (q.id to text) },
                    )
                }
            }

            if (!answered) {
                if (pendingQuestion == null) {
                    Text(
                        "问答通道未就绪（等模型重新提问即可）喵…",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    val requestId = pendingQuestion.requestId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isPlanReview) {
                            // 计划审阅两按钮：中文显示，原文 label 回传
                            Button(
                                onClick = {
                                    onAnswer(
                                        requestId,
                                        listOf(DshParams.QuestionAnswer(id = planQuestion?.id ?: PLAN_REVIEW_ID, selected = listOf(approveLabel))),
                                    )
                                },
                                enabled = canInteract,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) { Text("批准") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onAnswer(
                                        requestId,
                                        listOf(DshParams.QuestionAnswer(id = planQuestion?.id ?: PLAN_REVIEW_ID, selected = listOf(keepLabel))),
                                    )
                                },
                                enabled = canInteract,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) { Text("继续规划") }
                        } else {
                            Button(
                                onClick = {
                                    val answers = questions.map { q ->
                                        if (q.id in useCustom) {
                                            DshParams.QuestionAnswer(
                                                id = q.id,
                                                selected = emptyList(),
                                                custom = customTexts[q.id]?.trim(),
                                            )
                                        } else {
                                            DshParams.QuestionAnswer(
                                                id = q.id,
                                                selected = selections[q.id].orEmpty().toList(),
                                            )
                                        }
                                    }
                                    onAnswer(requestId, answers)
                                },
                                enabled = canInteract && submitReady,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) { Text("提交回答") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onCancel(requestId) },
                            enabled = canInteract,
                        ) { Text("先不打扰") }
                    }
                }
            }
        }
    }
}

/** 单个问题块：header 小标题 + question 正文（MD）+ 选项按钮列表（多选勾选）+ 「其他」文本框 */
@Composable
private fun QuestionBlock(
    question: QuestionItem,
    contentColor: Color,
    interactive: Boolean,
    selected: Set<String>,
    useCustom: Boolean,
    customText: String,
    onToggleOption: (String) -> Unit,
    onToggleCustom: () -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        question.header?.takeIf { it.isNotBlank() }?.let { header ->
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
        if (question.question.isNotBlank()) {
            MarkdownText(question.question, textColor = contentColor)
        }
        question.detail?.takeIf { it.isNotBlank() && it != question.question }?.let { detail ->
            MarkdownText(detail, textColor = contentColor.copy(alpha = 0.85f))
        }

        val optionControlColor = contentColor.copy(alpha = 0.9f)
        question.options.forEach { option ->
            val isSelected = option.label in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = interactive) { onToggleOption(option.label) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question.multiSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = if (interactive) {
                            { onToggleOption(option.label) }
                        } else null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = optionControlColor,
                            checkmarkColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = if (interactive) {
                            { onToggleOption(option.label) }
                        } else null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = optionControlColor,
                        ),
                        modifier = Modifier.size(32.dp),
                    )
                }
                Column {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = contentColor,
                    )
                    option.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }

        // 无 options 或点「其他」→ 单行文本框
        if (question.options.isEmpty() || interactive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = interactive && question.options.isNotEmpty()) { onToggleCustom() }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question.options.isNotEmpty()) {
                    Checkbox(
                        checked = useCustom,
                        onCheckedChange = if (interactive) {
                            { onToggleCustom() }
                        } else null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = optionControlColor,
                            checkmarkColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "其他",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                }
            }
            if (question.options.isEmpty() || useCustom) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = onCustomChange,
                    enabled = interactive,
                    singleLine = true,
                    placeholder = { Text("想说的直接告诉喵喵老师…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
    }
}
