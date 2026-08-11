package com.meow.academy.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import com.meow.academy.rpc.PiEventTypes
import com.meow.academy.rpc.RpcCommand
import com.meow.academy.rpc.str
import com.meow.academy.rpc.int
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

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
 * 终端页 ViewModel（M2.5 + Bug1 修复）。
 *
 * 走 RPC `bash` 命令执行（非真 PTY，见决策 3.2）：
 * 发送时带 id，`bash_execution_update`（delta 增量）流式累积，
 * response 返回最终 output / exitCode。
 *
 * ### 虚拟 cwd（Bug1 修复）
 * pi 的 session.cwd 固定、RPC 无 set_cwd 命令、每条 bash 都是独立 shell，
 * 因此 App 端维护 [cwd] 状态：
 *  - `cd` 命令在 App 端解析（更新 [cwd]，不发 RPC；发 `cd <目标> && pwd` 验证存在性）；
 *  - 其余命令执行时前缀 `cd "<cwd>" &&`，让每条独立 shell 都从当前虚拟目录开始。
 * 双入口初始化不同 cwd：文件管理 → 知识库目录；设置 → home（null 时用 homeDir）。
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val runtimeManager = (app as MeowAcademyApp).runtimeManager

    /** pi 视角的 home（= App 私有目录，PiProcessLauncher 里 HOME/filesDir 一致） */
    val homeDir: String = app.filesDir.absolutePath

    private val _cwd = MutableStateFlow(homeDir)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    private var currentCollector: Job? = null

    /** Pi 运行时状态描述（排障用） */
    val runtimeState = runtimeManager.state

    /** 设置初始工作目录（入口语境变化时由 UI 调用，如 文件管理=知识库 / 设置=home） */
    fun setCwd(cwd: String?) {
        _cwd.value = cwd?.takeIf { it.isNotBlank() } ?: homeDir
    }

    /**
     * 执行命令。`cd` 在 App 端解析并验证；其余命令前缀 `cd "<cwd>" &&` 后走 RPC bash。
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
                id = "err-${System.currentTimeMillis()}",
                command = cmd,
                output = "",
                error = "Pi 运行时未启动（设置 → 常驻开关 / 检查 API Key）",
            )
            return
        }

        val id = rpc.newId()
        val entry = TerminalEntry(id = id, command = trimmed)
        _entries.value = _entries.value + entry

        // 前缀 cd：让独立 shell 从当前虚拟目录开始
        val fullCommand = "cd \"${_cwd.value}\" && $trimmed"

        viewModelScope.launch {
            // 事件收集：与当前命令同 id 的 bash_execution_update → 累积 delta（不可变 copy 更新）
            currentCollector?.cancel()
            currentCollector = launch {
                rpc.events.collect { ev ->
                    if (ev.id == id && ev.type == PiEventTypes.BASH_EXECUTION_UPDATE) {
                        val delta = ev.raw.str("delta") ?: return@collect
                        _entries.value = _entries.value.map { e ->
                            if (e.id == id) e.copy(output = e.output + delta) else e
                        }
                    }
                }
            }

            val resp = rpc.send(
                RpcCommand(type = "bash", command = fullCommand, id = id),
                timeoutMs = 300_000,
            )
            currentCollector?.cancel()
            currentCollector = null

            if (resp?.success == true) {
                val data = resp.data
                _entries.value = _entries.value.map { e ->
                    if (e.id == id) {
                        e.copy(
                            output = data?.str("output") ?: e.output,
                            exitCode = data?.int("exitCode"),
                            cancelled = data?.str("cancelled")?.toBoolean() ?: false,
                        )
                    } else e
                }
            } else {
                _entries.value = _entries.value.map { e ->
                    if (e.id == id) e.copy(error = resp?.error ?: "bash 执行失败") else e
                }
            }
        }
    }

    /** cd 命令：App 端解析目标目录，发 `cd <目标> && pwd` 验证存在性，成功才更新 [cwd] */
    private fun runCd(cmd: String) {
        val target = cmd.removePrefix("cd").trim()
        val resolved = resolveCwd(_cwd.value, target)

        val rpc = runtimeManager.rpcClient
        if (rpc == null) {
            // 运行时未启动：至少本地记录（目录存在性无法验证）
            _cwd.value = resolved
            _entries.value = _entries.value + TerminalEntry(
                id = "cd-${System.currentTimeMillis()}",
                command = cmd,
                output = "",
                error = "Pi 运行时未启动，已本地切换目录（未验证）",
            )
            return
        }

        val id = rpc.newId()
        _entries.value = _entries.value + TerminalEntry(id = id, command = cmd)

        viewModelScope.launch {
            val resp = rpc.send(
                RpcCommand(type = "bash", command = "cd \"$resolved\" && pwd", id = id),
                timeoutMs = 30_000,
            )
            val success = resp?.success == true && resp?.data?.int("exitCode") == 0
            _entries.value = _entries.value.map { e ->
                if (e.id == id) {
                    if (success) {
                        e.copy(
                            output = resp?.data?.str("output") ?: resolved,
                            exitCode = 0,
                        )
                    } else {
                        e.copy(
                            output = resp?.data?.str("output") ?: "",
                            error = resp?.data?.str("output")?.lines()?.lastOrNull()
                                ?: resp?.error
                                ?: "目录不存在",
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
            target.startsWith("~/") -> "$homeDir/${target.removePrefix("~/")}"
            else -> target
        }
        val raw = if (expanded.startsWith("/")) expanded else "$current/$expanded"
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

    fun abortRunning() {
        viewModelScope.launch {
            runtimeManager.rpcClient?.sendFireAndForget(RpcCommand(type = "abort_bash"))
        }
    }

    fun clear() {
        currentCollector?.cancel()
        currentCollector = null
        _entries.value = emptyList()
    }
}
