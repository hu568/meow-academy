#!/usr/bin/env node
/**
 * terminal-host —— 喵仓真终端宿主（node 侧）。
 *
 * 用 node-pty（Android fork @mmmbuto/node-pty-android-arm64）fork 一个持久 bash（真终端），
 * 挂到 PTY 上；通过本地 unix socket 双向转发 PTY 裸字节流给 Kotlin 终端页；
 * bash 就绪后向其写入 DSH 启动命令（DSH 作为 bash 子进程后台运行，聊天走 DSH_JSONRPC_SOCKET）。
 *
 * 环境变量（由 App 注入，经 bash 继承给 DSH）：
 *   DSH_TERMINAL_SOCKET    PTY socket 路径（必填，本进程监听）
 *   DSH_JSONRPC_SOCKET     聊天 socket 路径（传给 DSH，meow-jsonrpc 监听）
 *   DSH_RUNTIME_DIR        runtime 根目录（含 node_modules + dsh/cordis.yml）
 *   DSH_NODE_BIN / DSH_BASH_BIN  node/bash 绝对路径（App 私有 ELF 须经 linker64 加载）
 *   DSH_CWD                工作目录（bash 与 DSH 的 cwd）
 *   DSH_TERMINAL_COLS/ROWS 终端初始行列（可选，默认 80x24）
 *   DSH_START_DSH          "0" 时不自动启动 DSH（调试用）；默认自动启动
 */
const net = require('node:net')
const path = require('node:path')

// 日志：同步写 stderr（App 侧转发到 logcat 的 DshStderr tag）
function log(msg) {
  const line = msg + String.fromCharCode(10)
  try { process.stderr.write(line) } catch {}
}

const pty = require('@mmmbuto/node-pty-android-arm64')

const socketPath = process.env.DSH_TERMINAL_SOCKET
const runtimeDir = process.env.DSH_RUNTIME_DIR
const cwd = process.env.DSH_CWD || process.cwd()
const cols = Number(process.env.DSH_TERMINAL_COLS || 80)
const rows = Number(process.env.DSH_TERMINAL_ROWS || 24)
const startDsh = process.env.DSH_START_DSH !== '0'

if (!socketPath) {
  log('[terminal-host] DSH_TERMINAL_SOCKET is required')
  process.exit(1)
}

// fork 真终端 bash：bash 是 App 私有 ELF，untrusted_app 直接 execvp 报 EACCES，须经 linker64 加载
const linker64 = process.env.DSH_LINKER64 || '/system/bin/linker64'
const bashBin = process.env.DSH_BASH_BIN || (runtimeDir ? path.join(runtimeDir, 'bin/bash') : 'bash')
let term
try {
  term = pty.spawn(linker64, [bashBin, '--norc', '--noprofile'], {
    name: 'xterm-256color', cols, rows, cwd,
    env: { ...process.env, TERM: 'xterm-256color', PS1: '\\w\\$ ' },
  })
} catch (e) {
  log('[terminal-host] bash spawn FAILED: ' + (e && e.stack || e))
  process.exit(1)
}
log('[terminal-host] bash spawned pid=' + term.pid)

const clients = new Set()
let dshStarted = false

/** 向 bash 写入 DSH 启动命令（后台运行；node 也是私有 ELF，须经 linker64 包装） */
function launchDsh() {
  if (dshStarted || !runtimeDir || !startDsh) return
  dshStarted = true
  const nodeBin = process.env.DSH_NODE_BIN || process.execPath
  const entry = path.join(runtimeDir, 'node_modules/@deepseek-ai/dsh-sdk-jsonrpc-demo/lib/packaged-bin.js')
  const config = path.join(runtimeDir, 'dsh/cordis.yml')
  term.write(`"${linker64}" "${nodeBin}" "${entry}" "${config}" &\r`)
}

// PTY 输出 → 广播给所有已连终端 socket
term.onData((data) => {
  for (const c of clients) {
    try { c.write(data) } catch {}
  }
})

term.onExit(() => { process.exit(0) })

// PTY socket：双向裸字节流（Kotlin 终端页读写）
const server = net.createServer((socket) => {
  clients.add(socket)
  socket.on('data', (buf) => { try { term.write(buf.toString('utf8')) } catch {} })
  socket.on('close', () => { clients.delete(socket) })
  socket.on('error', () => { clients.delete(socket) })
})

server.on('error', (e) => { log('[terminal-host] server error: ' + e) })
server.listen(socketPath, () => {
  log('[terminal-host] pty socket listening: ' + socketPath)
  setTimeout(launchDsh, 800)
})
