package com.meow.academy.ui.terminal

/** 终端渲染段：一段同色文本（ANSI 前景色已解析） */
data class TerminalSegment(val text: String, val fg: Int)
