package com.meow.academy.ui.chat

import android.util.Log
import com.meow.academy.data.chat.ChatDao
import com.meow.academy.rpc.DshEvent
import com.meow.academy.rpc.DshEventTypes
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.DshParams
import com.meow.academy.runtime.RuntimeManager
import com.meow.academy.rpc.bool
import com.meow.academy.rpc.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 能力状态控制器——附加模式(plan/goal)/todo/subagent/问答/水合（plan-chatviewmodel-refactor §2.1）。
 *
 * 状态所有权：attachedMode / todoState / subagentRuns / pendingQuestion / hydratedRoomId。
 *
 * 会话切换清态：不再由「一个函数手写清 4 态」，改由本控制器**自监听** SessionController 的
 * currentSessionId（drop 初始 null），变化即清能力态 + 触发 session/query 水合——状态归谁、谁负责清。
 *
 * 能力事件单点：ChatEventRouter 把 todo/plan/goal/question/subagent 事件路由到 [onCapabilityEvent]，
 * runStream 收集器不再重复处理这三支。
 */
class ChatCapabilityController(
    private val scope: CoroutineScope,
    private val dao: ChatDao,
    private val runtimeManager: RuntimeManager,
    private val sessionController: ChatSessionController,
    private val toast: (String) -> Unit,
) {
    /** 附加模式当前状态（null = 无附加；Plan/Goal + pending 区分生效中与已确认） */
    private val _attachedMode = MutableStateFlow<AttachedMode?>(null)
    val attachedMode: StateFlow<AttachedMode?> = _attachedMode.asStateFlow()

    /** 悬浮栏 todo 清单（todo/write 全量快照 / session/query 水合；null = 无数据不显示） */
    private val _todoState = MutableStateFlow<List<TodoItemView>?>(null)
    val todoState: StateFlow<List<TodoItemView>?> = _todoState.asStateFlow()

    /** 悬浮栏子代理运行清单（subagent.started/finished 实时通知；不做持久化水合） */
    private val _subagentRuns = MutableStateFlow<List<SubagentRun>>(emptyList())
    val subagentRuns: StateFlow<List<SubagentRun>> = _subagentRuns.asStateFlow()

    /** 待回答问答（session.question 通知；非空时问答卡交互启用，set-if-absent 防重连双投递） */
    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()

    /** 已成功水合（session/query）的 Room 会话 id；null/不同 = 待水合，DSH 转 Running 时重试 */
    private var hydratedRoomId: Long? = null

    init {
        // 会话切换 → 清能力态 + 触发水合（自监听，替代 resetSessionViewState 的四处手写调用）
        scope.launch {
            sessionController.currentSessionId
                .drop(1) // 跳过初始 null（StateFlow 本身 distinct）
                .collect { id ->
                    resetSessionViewState()
                    if (id != null) hydrateCurrentSession(id)
                }
        }
    }

    // ── 能力事件单点入口（ChatEventRouter 路由） ──

    /**
     * 能力事件处理（仅此一处）：
     * - session.question → 待回答问答（set-if-absent：同一 requestId 的重连双投递忽略）；
     * - subagent.started/finished → 悬浮栏子代理清单（按 parent+child 去重）；
     * - todo/write、plan/mode、goal/change（限当前会话）：空闲会话的 /plan、/goal 事件
     *   在流式收集器之外到达，只有全局路由能接住。
     */
    fun onCapabilityEvent(ev: DshEvent) {
        when (ev.type) {
            DshNotifMethods.SESSION_QUESTION -> {
                val requestId = ev.params.str("requestId") ?: return
                if (_pendingQuestion.value?.requestId == requestId) return // 重连双投递
                val questions = (ev.params["questions"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { el -> (el as? JsonObject)?.let(::parseQuestionItem) }
                _pendingQuestion.value = PendingQuestion(
                    requestId = requestId,
                    sessionId = ev.params.str("sessionId"),
                    questions = questions,
                )
            }
            DshEventTypes.SUBAGENT_STARTED -> {
                val parent = ev.parentSessionId ?: return
                val child = ev.childSessionId ?: return
                if (_subagentRuns.value.any { it.parentSessionId == parent && it.childSessionId == child }) return
                _subagentRuns.value = _subagentRuns.value + SubagentRun(parentSessionId = parent, childSessionId = child)
            }
            DshEventTypes.SUBAGENT_FINISHED -> {
                val parent = ev.parentSessionId ?: return
                val child = ev.childSessionId ?: return
                _subagentRuns.value = _subagentRuns.value.map { run ->
                    if (run.parentSessionId == parent && run.childSessionId == child) {
                        run.copy(
                            provider = ev.params.str("provider") ?: run.provider,
                            status = ev.params.str("status") ?: run.status,
                            stopReason = ev.params.str("stopReason") ?: run.stopReason,
                            lastMessage = ev.params.str("lastAssistantMessage") ?: run.lastMessage,
                        )
                    } else {
                        run
                    }
                }
            }
            DshEventTypes.TODO_WRITE -> {
                if (ev.sessionId == sessionController.dshSessionIdOf(sessionController.currentSessionId.value)) {
                    _todoState.value = parseTodoItems(ev.data?.get("todos") as? JsonArray)
                }
            }
            DshEventTypes.PLAN_MODE -> {
                if (ev.sessionId == sessionController.dshSessionIdOf(sessionController.currentSessionId.value)) applyPlanModeEvent(ev.data)
            }
            DshEventTypes.GOAL_CHANGE -> {
                if (ev.sessionId == sessionController.dshSessionIdOf(sessionController.currentSessionId.value)) applyGoalChangeEvent(ev.data)
            }
        }
    }

    // ── 附加模式（/plan、/goal 斜杠命令经 session/command，plan-standard-mode §5.3） ──

    /** 附加规划模式（/plan） */
    fun attachPlan() = runModeCommand("/plan", optimistic = AttachedMode.Plan(pending = true))

    /** 关闭规划模式（/plan off） */
    fun detachPlan() = runModeCommand("/plan off", optimistic = AttachedMode.Plan(pending = true))

    /** 附加目标模式（/goal <objective>；目标必填，会立即驱动一个模型回合） */
    fun attachGoal(objective: String) {
        val text = objective.trim()
        if (text.isEmpty()) {
            toast("目标不能为空喵~")
            return
        }
        runModeCommand("/goal $text", optimistic = AttachedMode.Goal(objective = text, pending = true))
    }

    /** 清除目标（/goal clear） */
    fun detachGoal() {
        val objective = (_attachedMode.value as? AttachedMode.Goal)?.objective.orEmpty()
        runModeCommand("/goal clear", optimistic = AttachedMode.Goal(objective = objective, pending = true))
    }

    /**
     * 执行附加模式斜杠命令（session/command，携带 Room 行的 presetId/cwd——会话未建时创建即归属正确）。
     *
     * 三态机：发出即入「生效中」（optimistic pending 态）→
     * - kind=success：保持生效中，状态确认以 plan/mode、goal/change 事件为准（命令成功 ≠ 已生效）；
     * - kind=error：回退原状态 + toast 原文；
     * - jsonrpc reject（null）：回退 + toast；COMMAND_UNKNOWN 按会话区分——
     *   Room 行 presetId == null（升级前旧会话）→「该会话不支持附加模式」，否则「命令不存在」。
     */
    private fun runModeCommand(line: String, optimistic: AttachedMode) {
        val roomId = sessionController.currentSessionId.value ?: run {
            toast("先新建一个会话再附加模式喵~")
            return
        }
        val rpc = runtimeManager.rpcClient ?: run {
            toast("DSH 未就绪，稍后再试喵~")
            return
        }
        val previous = _attachedMode.value
        _attachedMode.value = optimistic
        scope.launch {
            val session = dao.getSession(roomId)
            val result = rpc.sessionCommand(
                sessionController.dshSessionIdOf(roomId),
                line,
                presetId = session?.presetId,
                cwd = session?.workspacePath,
            )
            if (sessionController.currentSessionId.value != roomId) return@launch // 会话已切走，别动新会话状态
            when {
                result == null -> {
                    _attachedMode.value = previous
                    toast(
                        if (session?.presetId == null) "该会话不支持附加模式喵（升级前的旧会话）"
                        else "附加命令不存在喵（$line）"
                    )
                }
                result.str("kind") == "error" -> {
                    _attachedMode.value = previous
                    toast(result.str("text")?.takeIf { it.isNotBlank() } ?: "附加命令执行失败喵（$line）")
                }
                // kind=success 仅受理：等事件确认（pending 保持）
            }
        }
    }

    /** plan/mode 事件 → 附加模式状态（事件为准；active=true 确认 Plan，false 撤销） */
    private fun applyPlanModeEvent(data: JsonObject?) {
        val current = _attachedMode.value
        if (data?.bool("active") == true) {
            if (current !is AttachedMode.Plan || current.pending) {
                _attachedMode.value = AttachedMode.Plan(pending = false)
            }
        } else if (current is AttachedMode.Plan) {
            _attachedMode.value = null
        }
    }

    /**
     * goal/change 事件 → 附加模式状态。
     * 事件 data = GoalChangeMeta：{kind, version, operation, goal: GoalSnapshot, …}；
     * operation=clear 为墓碑（清除），快照变更从 goal.goal 取 objective/phase。
     */
    private fun applyGoalChangeEvent(data: JsonObject?) {
        if (data?.str("operation") == "clear") {
            if (_attachedMode.value is AttachedMode.Goal) _attachedMode.value = null
            return
        }
        val snapshot = data?.get("goal") as? JsonObject ?: return
        val objective = snapshot.str("objective") ?: return
        // 单槽位：goal 生效即覆盖 Plan（plan/goal 在 DSH 可共存，UI 先单槽）
        _attachedMode.value = AttachedMode.Goal(
            objective = objective,
            phase = snapshot.str("phase"),
            pending = false,
        )
    }

    // ── 问答动作（session/answerQuestion，§三.6） ──

    /** 提交回答：成功后清除待回答状态（问答卡转已答折叠态由 tool/result 驱动） */
    fun answerQuestion(requestId: String, answers: List<DshParams.QuestionAnswer>) {
        scope.launch {
            val rpc = runtimeManager.rpcClient ?: return@launch
            val ok = rpc.answerQuestion(requestId, answers)
            if (ok && _pendingQuestion.value?.requestId == requestId) {
                _pendingQuestion.value = null
            } else if (!ok) {
                toast("回答送达失败喵，请重试")
            }
        }
    }

    /** 先不打扰：取消当前问答（模型收到取消语义，plan 审阅转「用户想直接说话」） */
    fun cancelQuestion(requestId: String) {
        scope.launch {
            val rpc = runtimeManager.rpcClient ?: return@launch
            val ok = rpc.answerQuestion(requestId, cancelled = true)
            if (_pendingQuestion.value?.requestId == requestId) {
                _pendingQuestion.value = null
            }
            if (!ok) toast("取消失败喵，请重试")
        }
    }

    // ── 会话状态水合（session/query，resume 后胶囊/悬浮栏恢复，§3.7） ──

    /**
     * 对指定会话做一次 session/query 水合（attachedMode / todoState；subagent 不水合）。
     * 失败（DSH 未就绪 / 超时）不置 hydratedRoomId → 转 Running 时自动重试一次。
     */
    fun hydrateCurrentSession(roomId: Long) {
        if (hydratedRoomId == roomId) return
        val rpc = runtimeManager.rpcClient ?: return
        scope.launch {
            val result = rpc.sessionQuery(sessionController.dshSessionIdOf(roomId)) ?: return@launch
            if (sessionController.currentSessionId.value != roomId) return@launch // 已切走，丢弃
            hydratedRoomId = roomId
            _todoState.value = parseTodoItems(result["todos"] as? JsonArray)
            val plan = result["plan"] as? JsonObject
            val goal = result["goal"] as? JsonObject
            _attachedMode.value = when {
                plan?.bool("active") == true -> AttachedMode.Plan(pending = false)
                goal != null -> {
                    val snapshot = goal["goal"] as? JsonObject
                    AttachedMode.Goal(
                        objective = snapshot?.str("objective").orEmpty(),
                        phase = snapshot?.str("phase"),
                        pending = false,
                    )
                }
                else -> null
            }
        }
    }

    /** 新开/新建会话后的视图状态复位（todo / 附加模式 / 子代理清单） */
    private fun resetSessionViewState() {
        _todoState.value = null
        _attachedMode.value = null
        _subagentRuns.value = emptyList()
        hydratedRoomId = null
    }

    /** 解析 todo 数组（[{content, status}]）→ 悬浮栏视图模型；null/缺字段 → null（不显示） */
    private fun parseTodoItems(arr: JsonArray?): List<TodoItemView>? {
        if (arr == null) return null
        return arr.mapNotNull { el ->
            (el as? JsonObject)?.let { obj ->
                val content = obj.str("content") ?: return@mapNotNull null
                TodoItemView(content = content, status = obj.str("status") ?: "pending")
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** session.question 的单条问题解析（AskUserQuestionItem 视图） */
    private fun parseQuestionItem(obj: JsonObject): QuestionItem? {
        val id = obj.str("id") ?: return null
        val intent = obj["intent"] as? JsonObject
        val options = (obj["options"] as? JsonArray)?.mapNotNull { o ->
            (o as? JsonObject)?.let { OptionItem(label = it.str("label") ?: "", description = it.str("description")) }
        }.orEmpty()
        return QuestionItem(
            id = id,
            question = obj.str("question") ?: "",
            detail = obj.str("detail"),
            header = obj.str("header"),
            options = options,
            multiSelect = obj.bool("multiSelect") == true,
            intentKind = intent?.str("kind"),
            intentApprove = intent?.str("approve"),
        )
    }
}