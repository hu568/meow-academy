package com.meow.academy.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import com.meow.academy.rpc.DshNotifMethods
import com.meow.academy.rpc.bool
import com.meow.academy.rpc.int
import com.meow.academy.rpc.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 终端条目：一条命令 + 其输出（不可变，更新用 copy 保证 StateFlow 触发重组） */
data class TerminalEntry(
    val id: String,
    val command: String,
    val output: String = "",
    val exitCode: Int? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
) {
    val isRunning: Boolean get() = exitCode == null && error == null
}

/**
 * 终端页 ViewModel（DSH 版）。
 *
 * 走 meow-jsonrpc 插件的 `session/bash` 方法（非真 PTY，体验同现状）：
 *  - 输出经 session.bashOutput 通知按 requestId 流式累积；
 *  - 响应返回最终 exitCode / status / timedOut / cancelled；
 *  - 命令的工作目录用 workdir 参数指定（不再前缀 cd &&）。
 *
 * ### 虚拟 cwd（Bug1 修复的延续）
 * 每条 bash 都是独立 shell，因此 App 端维护 [cwd] 状态：
 *  - `cd` 命令在 App 端解析（更新 [cwd]，不发执行；发 `pwd` 带 workdir 验证存在性）；
 *  - 其余命令带 workdir=[cwd]，让每条独立 shell 都从当前虚拟目录开始。
 * 双入口初始化不同 cwd：文件管理 → 知识库目录；设置 → home（null 时用 homeDir）。
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val runtimeManager = (app as MeowAcademyApp).runtimeManager

    /** DSH 视角的 home（= App 私有目录，DshProcessLauncher 里 HOME/filesDir 一致） */
    val homeDir: String = app.filesDir.absolutePath

    private val _cwd = MutableStateFlow(homeDir)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    private var currentCollector: Job? = null

    /** 最近一条终端命令的 requestId（abortRunning 用） */
    private var lastRequestId: String? = null

    /** DSH 运行时状态描述（排障用） */
    val runtimeState = runtimeManager.state

    /** 设置初始工作目录（入口语境变化时由 UI 调用，如 文件管理=知识库 / 设置=home） */
    fun setCwd(cwd: String?) {
        _cwd.value = cwd?.takeIf { it.isNotBlank() } ?: homeDir
    }

    /**
     * 执行命令。`cd` 在 App 端解析并验证；其余命令带 workdir=[cwd] 走 session/bash。
     */
    fun runCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return

        // ── cd 解析（虚拟 cwd）──
        if (trimmed == "cd" || trimmed.startsWith("cd ")) {
            runCd(trimmed)
            return
        }

        val rpc = runtimeManager.rpcClient
        if (rpc == null) {
            _entries.value = _entries.value + TerminalEntry(
                id = "err-" + System.currentTimeMillis(),
                command = cmd,
                output = "",
                error = "DSH 运行时未启动（设置 → 常驻开关 / 检查 API Key）",
            )
            return
        }

        val id = rpc.newId()
        val entry = TerminalEntry(id = id, command = trimmed)
        _entries.value = _entries.value + entry
        lastRequestId = id

        viewModelScope.launch {
            // 事件收集：同 requestId 的 session.bashOutput → 累积 delta（不可变 copy 更新）
            currentCollector?.cancel()
            currentCollector = launch {
                rpc.events
                    .filter { it.method == DshNotifMethods.BASH_OUTPUT && it.requestId == id }
                    .collect { ev ->
                        val delta = ev.delta ?: return@collect
                        _entries.value = _entries.value.map { e ->
                            if (e.id == id) e.copy(output = e.output + delta) else e
                        }
                    }
            }

            // 执行：workdir 直接指定虚拟 cwd；超时 300s 与服务端 maxTimeoutMs 对齐
            val result = rpc.bash(
                requestId = id,
                command = trimmed,
                workdir = _cwd.value,
                timeoutMs = 300_000,
                awaitTimeoutMs = 310_000,
            )
            currentCollector?.cancel()
            currentCollector = null

            if (result != null) {
                val cancelled = result.bool("cancelled") == true || result.bool("timedOut") == true
                _entries.value = _entries.value.map { e ->
                    if (e.id == id) {
                        // 被中止时 exitCode 常为 null（信号杀死），用 -1 兜底结束 running 状态
                        e.copy(
                            exitCode = result.int("exitCode") ?: if (cancelled) -1 else null,
                            cancelled = cancelled,
                        )
                    } else e
                }
            } else {
                _entries.value = _entries.value.map { e ->
                    if (e.id == id) e.copy(error = "bash 执行失败（超时/断连）") else e
                }
            }
        }
    }

    /** cd 命令：App 端解析目标目录，发 `pwd`（workdir=目标）验证存在性，成功才更新 [cwd] */
    private fun runCd(cmd: String) {
        val target = cmd.removePrefix("cd").trim()
        val resolved = resolveCwd(_cwd.value, target)

        val rpc = runtimeManager.rpcClient
        if (rpc == null) {
            // 运行时未启动：至少本地记录（目录存在性无法验证）
            _cwd.value = resolved
            _entries.value = _entries.value + TerminalEntry(
                id = "cd-" + System.currentTimeMillis(),
                command = cmd,
                output = "",
                error = "DSH 运行时未启动，已本地切换目录（未验证）",
            )
            return
        }

        val id = rpc.newId()
        _entries.value = _entries.value + TerminalEntry(id = id, command = cmd)

        viewModelScope.launch {
            val result = rpc.bash(
                requestId = id,
                command = "pwd",
                workdir = resolved,
                timeoutMs = 30_000,
                awaitTimeoutMs = 40_000,
            )
            val success = result != null && result.int("exitCode") == 0
            _entries.value = _entries.value.map { e ->
                if (e.id == id) {
                    if (success) {
                        e.copy(output = resolved, exitCode = 0)
                    } else {
                        e.copy(
                            output = "",
                            error = "目录不存在或无权限",
                        )
                    }
                } else e
            }
            if (success) _cwd.value = resolved
        }
    }

    /** 解析 cd 目标 → 绝对路径（支持 ~ / ~/x / 相对 / 绝对 / ..，手动 normalize） */
    private fun resolveCwd(current: String, target: String): String {
        val expanded = when {
            target.isEmpty() -> homeDir
            target == "~" -> homeDir
            target.startsWith("~/") -> homeDir + "/" + target.removePrefix("~/")
            else -> target
        }
        val raw = if (expanded.startsWith("/")) expanded else current + "/" + expanded
        return normalizePath(raw)
    }

    /** 路径规范化：处理 `.` / `..` / 连续斜杠 */
    private fun normalizePath(path: String): String {
        val stack = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    /** 中止正在运行的终端命令（session/bashCancel） */
    fun abortRunning() {
        val id = lastRequestId ?: return
        viewModelScope.launch {
            runtimeManager.rpcClient?.bashCancel(id)
        }
    }

    fun clear() {
        currentCollector?.cancel()
        currentCollector = null
        lastRequestId = null
        _entries.value = emptyList()
    }
}
