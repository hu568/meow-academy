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
 * 终端页 ViewModel（M2.5）。
 *
 * 走 RPC `bash` 命令执行（非真 PTY，见决策 3.2）：
 * 发送时带 id，`bash_execution_update`（delta 增量）流式累积，
 * response 返回最终 output / exitCode。
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val runtimeManager = (app as MeowAcademyApp).runtimeManager

    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    private var currentCollector: Job? = null

    /** Pi 运行时状态描述（排障用） */
    val runtimeState = runtimeManager.state

    fun runCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return

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
                RpcCommand(type = "bash", command = trimmed, id = id),
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
