package com.meow.academy.ui.terminal

import android.app.Application
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meow.academy.MeowAcademyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 终端页 ViewModel（真终端 PTY 版）。
 *
 * 直连 terminal-host 的 PTY unix socket（DSH_TERMINAL_SOCKET）：
 *  - PTY 输出流交给 [AnsiScreen] 解析（SGR 颜色 / CUP 光标 / ED 清屏 / CR/LF/BS/TAB）并渲染；
 *  - 输入直接写入 PTY（bash 维护真实 cwd，无需虚拟 cwd）。
 *
 * 真终端意味着：cd 持久、vim/top 可跑、交互程序正常。
 * 屏幕缓冲与 ANSI 解析已拆到 AnsiScreen（纯逻辑），本类只负责连接与状态流。
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as MeowAcademyApp

    private var socket: LocalSocket? = null
    private var readJob: Job? = null

    private val screen = AnsiScreen()

    /** 渲染输出：每行是若干同色段 */
    private val _lines = MutableStateFlow<List<List<TerminalSegment>>>(emptyList())
    val lines: StateFlow<List<List<TerminalSegment>>> = _lines.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 运行时状态（排障） */
    val runtimeState = application.runtimeManager.state

    /**
     * 连接 PTY socket 并开始读循环。
     * @param initialDir 非空时连接成功后先执行 `cd -- '<路径>'`（如文件管理页的当前浏览目录），
     *                   null 则留在 bash 默认 cwd（filesDir 根 = DSH_CWD）。
     */
    fun start(initialDir: String? = null) {
        if (readJob != null) return
        readJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = File(application.filesDir, "dsh-terminal.sock").absolutePath
                val s = LocalSocket()
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                s.soTimeout = 0
                socket = s
                _connected.value = true
                // 连接成功、进入读循环前：自动 cd 到入口目录（单引号转义，路径含空格/中文安全）
                if (!initialDir.isNullOrEmpty()) {
                    val cmd = "cd -- '${initialDir.replace("'", "'\\''")}'\r"
                    try {
                        s.outputStream.write(cmd.toByteArray(Charsets.UTF_8))
                        s.outputStream.flush()
                    } catch (e: Exception) {
                        // 断连
                    }
                }
                val buf = ByteArray(4096)
                while (true) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n, Charsets.UTF_8)
                    screen.process(chunk)
                    _lines.value = screen.render()
                }
            } catch (e: Exception) {
                // 断连（runtime 退出 / socket 关闭）
            } finally {
                _connected.value = false
                socket?.close()
                socket = null
                readJob = null
            }
        }
    }

    /** 发送输入到 PTY（命令 + 回车） */
    fun sendInput(text: String) {
        val s = socket ?: return
        try {
            s.outputStream.write((text + "\r").toByteArray(Charsets.UTF_8))
            s.outputStream.flush()
        } catch (e: Exception) {
            // 断连
        }
    }

    /** 发送 Ctrl-C（中止前台程序，如 vim/top/卡住的命令） */
    fun sendInterrupt() {
        val s = socket ?: return
        try {
            s.outputStream.write(byteArrayOf(0x03))
            s.outputStream.flush()
        } catch (e: Exception) {
        }
    }

    fun clearScreen() {
        screen.clear()
        _lines.value = screen.render()
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        socket?.close()
        socket = null
        _connected.value = false
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
