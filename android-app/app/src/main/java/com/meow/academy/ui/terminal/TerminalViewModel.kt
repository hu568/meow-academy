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

/** 终端渲染段：一段同色文本（ANSI 前景色已解析） */
data class TerminalSegment(val text: String, val fg: Int)

/** 终端单格：字符 + 前景色 */
private data class Cell(var ch: Char = ' ', var fg: Int = DEFAULT_FG)

/** 默认前景色（浅灰白） */
private const val DEFAULT_FG = 0xFFE6EDF3.toInt()

/**
 * 终端页 ViewModel（真终端 PTY 版）。
 *
 * 直连 terminal-host 的 PTY unix socket（DSH_TERMINAL_SOCKET），维护一个屏幕缓冲区：
 *  - PTY 输出流经 ANSI/VT100 转义序列解析（SGR 颜色 / CUP 光标 / ED 清屏 / CR/LF/BS/TAB），
 *    更新屏幕网格；
 *  - 输入直接写入 PTY（bash 维护真实 cwd，无需虚拟 cwd）。
 *
 * 真终端意味着：cd 持久、vim/top 可跑、交互程序正常。
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as MeowAcademyApp

    private var socket: LocalSocket? = null
    private var readJob: Job? = null

    private val COLS = 80
    private val ROWS = 24
    private val grid = Array(ROWS) { Array(COLS) { Cell() } }
    private var curRow = 0
    private var curCol = 0

    /** 渲染输出：每行是若干同色段 */
    private val _lines = MutableStateFlow<List<List<TerminalSegment>>>(emptyList())
    val lines: StateFlow<List<List<TerminalSegment>>> = _lines.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 运行时状态（排障） */
    val runtimeState = application.runtimeManager.state

    /** 连接 PTY socket 并开始读循环 */
    fun start() {
        if (readJob != null) return
        readJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = File(application.filesDir, "dsh-terminal.sock").absolutePath
                val s = LocalSocket()
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                s.soTimeout = 0
                socket = s
                _connected.value = true
                val buf = ByteArray(4096)
                while (true) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n, Charsets.UTF_8)
                    process(chunk)
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
        for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = Cell()
        curRow = 0
        curCol = 0
        render()
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

    // ── ANSI 解析 ──
    private var escBuf = StringBuilder()
    private var inEsc = false
    private var csiParams = ""
    private var inCsi = false

    private fun process(chunk: String) {
        var i = 0
        while (i < chunk.length) {
            val ch = chunk[i]
            when {
                inEsc -> {
                    when (ch) {
                        '[' -> { inEsc = false; inCsi = true; csiParams = "" }
                        else -> { inEsc = false }
                    }
                }
                inCsi -> {
                    if (ch in '0'..'9' || ch == ';' || ch == '?') {
                        csiParams += ch
                    } else {
                        handleCsi(ch)
                        inCsi = false
                    }
                }
                ch == '\u001b' -> { inEsc = true }
                ch == '\r' -> curCol = 0
                ch == '\n' -> lineFeed()
                ch == '\b' -> if (curCol > 0) curCol--
                ch == '\t' -> curCol = minOf(COLS - 1, (curCol / 8 + 1) * 8)
                else -> putChar(ch)
            }
            i++
        }
        render()
    }

    private fun handleCsi(final: Char) {
        val parts = csiParams.split(';').map { it.toIntOrNull() ?: 0 }
        when (final) {
            'H', 'f' -> {
                curRow = (parts.getOrNull(0) ?: 1).coerceIn(1, ROWS) - 1
                curCol = (parts.getOrNull(1) ?: 1).coerceIn(1, COLS) - 1
            }
            'A' -> curRow = (curRow - (parts.getOrNull(0) ?: 1)).coerceAtLeast(0)
            'B' -> curRow = (curRow + (parts.getOrNull(0) ?: 1)).coerceAtMost(ROWS - 1)
            'C' -> curCol = (curCol + (parts.getOrNull(0) ?: 1)).coerceAtMost(COLS - 1)
            'D' -> curCol = (curCol - (parts.getOrNull(0) ?: 1)).coerceAtLeast(0)
            'J' -> clearDisplay(parts.getOrNull(0) ?: 0)
            'K' -> clearLine(parts.getOrNull(0) ?: 0)
            'm' -> applySgr(parts)
            else -> {} // 忽略未支持的 CSI（DEC 等）
        }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty() || params.all { it == 0 }) {
            grid[curRow][curCol].fg = DEFAULT_FG
            return
        }
        // 只处理前景色；存储到「当前行」的后续字符（简化：无持久颜色态，用下一个字符的颜色）
        // 为支持多字符着色，这里维护一个「当前前景色」，由 putChar 使用
        var fg = currentFg
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> fg = DEFAULT_FG
                1 -> {} // bold 忽略
                in 30..37 -> fg = ANSI_COLORS[p - 30]
                in 90..97 -> fg = ANSI_BRIGHT[p - 90]
                39 -> fg = DEFAULT_FG
            }
            i++
        }
        currentFg = fg
    }

    private var currentFg = DEFAULT_FG

    private fun putChar(ch: Char) {
        if (ch.code < 32) return
        if (curCol >= COLS) { curCol = 0; lineFeed() }
        val cell = grid[curRow][curCol]
        cell.ch = ch
        cell.fg = currentFg
        curCol++
    }

    private fun lineFeed() {
        if (curRow < ROWS - 1) {
            curRow++
        } else {
            scrollUp()
        }
    }

    private fun scrollUp() {
        for (r in 0 until ROWS - 1) grid[r] = grid[r + 1]
        grid[ROWS - 1] = Array(COLS) { Cell() }
    }

    private fun clearDisplay(mode: Int) {
        when (mode) {
            2 -> for (r in 0 until ROWS) for (c in 0 until COLS) grid[r][c] = Cell()
            0 -> for (c in curCol until COLS) grid[curRow][c] = Cell()
            1 -> for (c in 0..curCol) grid[curRow][c] = Cell()
        }
    }

    private fun clearLine(mode: Int) {
        when (mode) {
            0 -> for (c in curCol until COLS) grid[curRow][c] = Cell()
            1 -> for (c in 0..curCol) grid[curRow][c] = Cell()
            2 -> for (c in 0 until COLS) grid[curRow][c] = Cell()
        }
    }

    /** 屏幕网格 → 渲染行（每行按同色连续字符合并成段） */
    private fun render() {
        val out = mutableListOf<List<TerminalSegment>>()
        for (r in 0 until ROWS) {
            val segs = mutableListOf<TerminalSegment>()
            var sb = StringBuilder()
            var fg = DEFAULT_FG
            for (c in 0 until COLS) {
                val cell = grid[r][c]
                if (cell.fg != fg) {
                    if (sb.isNotEmpty()) { segs.add(TerminalSegment(sb.toString(), fg)); sb = StringBuilder() }
                    fg = cell.fg
                }
                sb.append(cell.ch)
            }
            if (sb.isNotEmpty()) segs.add(TerminalSegment(sb.toString(), fg))
            out.add(segs)
        }
        _lines.value = out
    }

    companion object {
        private val ANSI_COLORS = intArrayOf(
            0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
            0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
        )
        private val ANSI_BRIGHT = intArrayOf(
            0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
            0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
