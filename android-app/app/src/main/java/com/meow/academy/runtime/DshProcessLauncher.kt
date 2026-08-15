package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 在 Android 应用私有目录里直接执行内置 node（Termux 版动态链接二进制），
 * 入口为喵学堂真终端宿主：node + terminal-host.js。
 *
 * terminal-host 用 node-pty 拉起持久 bash（真终端），并在 bash 内后台启动 DSH；
 * DSH 的聊天 JSON-RPC 走本地 unix socket（DSH_JSONRPC_SOCKET），真终端数据走
 * DSH_TERMINAL_SOCKET，两者都不占用 stdio（stdio 已被 PTY 占用）。
 *
 * 关键点：
 *  - node / bash 二进制位于 filesDir/meow-runtime/bin/（自包含，见 build-runtime.sh）；
 *  - DSH closure 位于 filesDir/meow-runtime/node_modules/；
 *  - 通过环境变量注入 API Key / HOME / PATH / socket 路径 / 会话与 cwd。
 */
object DshProcessLauncher {

    private const val TAG = "DshProcessLauncher"

    /** 真终端宿主入口（相对 runtime 根目录） */
    private const val ENTRY_REL = "bin/terminal-host.js"

    /**
     * 拉起真终端宿主（terminal-host）：通过 `/system/bin/linker64` 加载内置 node。
     *
     * @param terminalSocket 真终端 PTY 数据 socket 路径（terminal-host 监听）
     * @param jsonRpcSocket  DSH 聊天 JSON-RPC socket 路径（meow-jsonrpc 监听）
     * @throws IOException 进程拉起失败
     */
    fun launch(
        context: Context,
        apiKey: String,
        terminalSocket: String,
        jsonRpcSocket: String,
    ): Process {
        val runtimeDir = RuntimeExtractor.runtimeDir(context)
        val node = File(runtimeDir, "bin/node")
        val entry = File(runtimeDir, ENTRY_REL)
        if (!node.exists() || !entry.exists()) {
            throw IOException(
                "runtime 不完整：node=" + node.exists() + " terminal-host=" + entry.exists()
            )
        }

        val command = listOf(
            "/system/bin/linker64",
            node.absolutePath,
            entry.absolutePath,
        )
        Log.i(TAG, "launch: " + command.joinToString(" "))

        val pb = ProcessBuilder(command)
        pb.directory(context.filesDir)
        pb.environment().apply {
            // PATH：runtime/bin 里有 node/bash；/system/bin 提供 sh 等系统命令
            put("PATH", runtimeDir.absolutePath + "/bin:/system/bin:/system/xbin")
            put("HOME", context.filesDir.absolutePath)
            // node/bash 是动态链接 Termux 库，需指向 runtime 内置的 .so
            put("LD_LIBRARY_PATH", runtimeDir.absolutePath + "/lib")
            // Termux 版 node 的 OpenSSL 默认 CA 路径在 App 沙箱不可读，重定向到 runtime 内置 CA 束
            put("OPENSSL_CONF", runtimeDir.absolutePath + "/etc/tls/openssl.cnf")
            put("NODE_EXTRA_CA_CERTS", runtimeDir.absolutePath + "/etc/tls/cert.pem")
            // DNS 兜底 shim（App 沙箱内 getaddrinfo 走不了 netd 解析）
            put("NODE_OPTIONS", "--require " + runtimeDir.absolutePath + "/lib/dns-shim.js")
            // 真终端与聊天 socket 路径 + runtime 根目录（terminal-host 读取并传给 DSH）
            put("DSH_TERMINAL_SOCKET", terminalSocket)
            put("DSH_JSONRPC_SOCKET", jsonRpcSocket)
            put("DSH_RUNTIME_DIR", runtimeDir.absolutePath)
            // DSH 会话持久化（JSONL）与默认 cwd（cordis.yml 里经 DSH_SESSION_ROOT / DSH_CWD 读取）
            put("DSH_SESSION_ROOT", context.filesDir.absolutePath + "/.dsh-sessions")
            put("DSH_CWD", context.filesDir.absolutePath)
            if (apiKey.isNotBlank()) {
                put("DEEPSEEK_API_KEY", apiKey)
            }
        }
        return pb.start()
    }
}
