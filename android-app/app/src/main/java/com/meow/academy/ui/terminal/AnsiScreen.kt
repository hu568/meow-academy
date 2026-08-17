package com.meow.academy.ui.terminal

/** 终端单格：字符 + 前景色 */
private data class Cell(var ch: Char = ' ', var fg: Int = DEFAULT_FG)

/** 默认前景色（浅灰白） */
private const val DEFAULT_FG = 0xFFE6EDF3.toInt()

/**
 * 终端屏幕缓冲 + ANSI/VT100 解析引擎（纯逻辑，不依赖 Android）。
 *
 * 职责：
 *  - 输入流经 ANSI/VT100 转义序列解析（SGR 颜色 / CUP 光标 / ED 清屏 / CR/LF/BS/TAB），
 *    更新屏幕网格；
 *  - [render] 把网格合并成同色段序列，供 UI 渲染。
 *
 * 连接与 I/O 由 TerminalViewModel 负责，本类只维护屏幕状态。
 */
class AnsiScreen(
    private val cols: Int = 80,
    private val rows: Int = 24,
) {

    private val grid = Array(rows) { Array(cols) { Cell() } }
    private var curRow = 0
    private var curCol = 0
    private var currentFg = DEFAULT_FG

    // ── ANSI 解析状态 ──
    private var escBuf = StringBuilder()
    private var inEsc = false
    private var csiParams = ""
    private var inCsi = false

    /** 消费一段 PTY 输出（可能含多条转义序列） */
    fun process(chunk: String) {
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
                ch == '\t' -> curCol = minOf(cols - 1, (curCol / 8 + 1) * 8)
                else -> putChar(ch)
            }
            i++
        }
    }

    /** 清空屏幕（回到左上角） */
    fun clear() {
        for (r in 0 until rows) for (c in 0 until cols) grid[r][c] = Cell()
        curRow = 0
        curCol = 0
    }

    /** 屏幕网格 → 渲染行（每行按同色连续字符合并成段） */
    fun render(): List<List<TerminalSegment>> {
        val out = mutableListOf<List<TerminalSegment>>()
        for (r in 0 until rows) {
            val segs = mutableListOf<TerminalSegment>()
            var sb = StringBuilder()
            var fg = DEFAULT_FG
            for (c in 0 until cols) {
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
        return out
    }

    private fun handleCsi(final: Char) {
        val parts = csiParams.split(';').map { it.toIntOrNull() ?: 0 }
        when (final) {
            'H', 'f' -> {
                curRow = (parts.getOrNull(0) ?: 1).coerceIn(1, rows) - 1
                curCol = (parts.getOrNull(1) ?: 1).coerceIn(1, cols) - 1
            }
            'A' -> curRow = (curRow - (parts.getOrNull(0) ?: 1)).coerceAtLeast(0)
            'B' -> curRow = (curRow + (parts.getOrNull(0) ?: 1)).coerceAtMost(rows - 1)
            'C' -> curCol = (curCol + (parts.getOrNull(0) ?: 1)).coerceAtMost(cols - 1)
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

    private fun putChar(ch: Char) {
        if (ch.code < 32) return
        if (curCol >= cols) { curCol = 0; lineFeed() }
        val cell = grid[curRow][curCol]
        cell.ch = ch
        cell.fg = currentFg
        curCol++
    }

    private fun lineFeed() {
        if (curRow < rows - 1) {
            curRow++
        } else {
            scrollUp()
        }
    }

    private fun scrollUp() {
        for (r in 0 until rows - 1) grid[r] = grid[r + 1]
        grid[rows - 1] = Array(cols) { Cell() }
    }

    private fun clearDisplay(mode: Int) {
        when (mode) {
            2 -> for (r in 0 until rows) for (c in 0 until cols) grid[r][c] = Cell()
            0 -> for (c in curCol until cols) grid[curRow][c] = Cell()
            1 -> for (c in 0..curCol) grid[curRow][c] = Cell()
        }
    }

    private fun clearLine(mode: Int) {
        when (mode) {
            0 -> for (c in curCol until cols) grid[curRow][c] = Cell()
            1 -> for (c in 0..curCol) grid[curRow][c] = Cell()
            2 -> for (c in 0 until cols) grid[curRow][c] = Cell()
        }
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
