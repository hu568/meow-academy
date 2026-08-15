#!/usr/bin/env node
/**
 * terminal-host —— 喵学堂真终端宿主（node 侧）。
 *
 * 用 node-pty（Android fork @mmmbuto/node-pty-android-arm64）fork 一个持久 bash（真终端），
 * 挂到 PTY 上；通过本地 unix socket 双向转发 PTY 裸字节流给 Kotlin 终端页；
 * bash 就绪后向其写入 DSH 启动命令（DSH 作为 bash 子进程后台运行，聊天走另一个 socket）。
 *
 * 环境变量（由 App 注入，经 bash 继承给 DSH）：
 *   DSH_TERMINAL_SOCKET    PTY socket 路径（必填，本进程监听）
 *   DSH_JSONRPC_SOCKET     聊天 socket 路径（传给 DSH，meow-jsonrpc 监听）
 *   DSH_RUNTIME_DIR        runtime 根目录（含 node_modules + dsh/cordis.yml）
 *   DSH_CWD                工作目录（bash 与 DSH 的 cwd）
 *   DSH_TERMINAL_COLS/ROWS 终端初始行列（可选，默认 80x24）
 *   DSH_START_DSH          '0' 时不自动启动 DSH（调试用）；默认自动启动
 */

const pty = require('@mmmbuto/node-pty-android-arm64')
const net = require('node:net')
const path = require('node:path')

const socketPath = process.env.DSH_TERMINAL_SOCKET
const runtimeDir = process.env.DSH_RUNTIME_DIR
const cwd = process.env.DSH_CWD || process.cwd()
const cols = Number(process.env.DSH_TERMINAL_COLS || 80)
const rows = Number(process.env.DSH_TERMINAL_ROWS || 24)
const startDsh = process.env.DSH_START_DSH !== '0'

if (!socketPath) {
  console.error('terminal-host: DSH_TERMINAL_SOCKET is required')
  process.exit(1)
}

// fork 真终端 bash（交互式，跳过 rc/profile 加快启动）
const term = pty.spawn('bash', ['--norc', '--noprofile'], {
  name: 'xterm-256color',
  cols,
  rows,
  cwd,
  env: { ...process.env, TERM: 'xterm-256color' },
})

const clients = new Set()
let dshStarted = false

/** 向 bash 写入 DSH 启动命令（后台运行，stdout/stderr 走 PTY → 终端页可见日志） */
function launchDsh() {
  if (dshStarted || !runtimeDir || !startDsh) return
  dshStarted = true
  const nodeBin = process.execPath // runtime/bin/node（本进程自身用的 node）
  const entry = path.join(runtimeDir, 'node_modules/@deepseek-ai/dsh-sdk-jsonrpc-demo/lib/packaged-bin.js')
  const config = path.join(runtimeDir, 'dsh/cordis.yml')
  // 后台运行；DSH 的 JSON-RPC 走 DSH_JSONRPC_SOCKET（环境变量已继承），stdio 仅用于日志
  term.write(`\"${nodeBin}\" \"${entry}\" \"${config}\" &\r`)
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
  socket.on('data', (buf) => {
    try { term.write(buf.toString('utf8')) } catch {}
  })
  socket.on('close', () => { clients.delete(socket) })
  socket.on('error', () => { clients.delete(socket) })
})

server.on('error', () => {})
server.listen(socketPath, () => {
  // 等 bash 就绪（prompt 出现）后启动 DSH
  setTimeout(launchDsh, 800)
})
