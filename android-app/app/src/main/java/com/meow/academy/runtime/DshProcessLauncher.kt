package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 在 Android 应用私有目录里直接执行内置 node（Termux 版动态链接二进制），
 * 入口为喵仓真终端宿主：node + terminal-host.js。
 *
 * terminal-host 用 node-pty 拉起持久 bash（真终端），并在 bash 内后台启动 DSH；
 * DSH 的聊天 JSON-RPC 走本地 unix socket（DSH_JSONRPC_SOCKET），真终端数据走
 * DSH_TERMINAL_SOCKET，两者都不占用 stdio（stdio 已被 PTY 占用）。
 *
 * 关键点：
 *  - node / bash 真 ELF 位于 filesDir/meow-runtime/lib/（私有 ELF 须经 linker64 加载），
 *    bin/ 下是 wrapper 脚本（PATH 命中用，经 MEOW_RUNTIME_DIR 定位，见 build-runtime.sh）；
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
        webSearchEnabled: Boolean = false,
    ): Process {
        // 兜底：runtime 已解压（跳过 extract）时也确保业务目录存在（三保险之三）
        RuntimeExtractor.ensureAppDirs(context)
        // 系统预设播种（assets dsh-presets/ → filesDir/dsh-presets/）：必须赶在 DSH 读 roots 之前
        RuntimeExtractor.syncDshPresetsIfNeeded(context)

        val runtimeDir = RuntimeExtractor.runtimeDir(context)
        val node = File(runtimeDir, "lib/node.bin")
        val entry = File(runtimeDir, ENTRY_REL)
        if (!node.exists() || !entry.exists()) {
            throw IOException(
                "runtime 不完整：node=" + node.exists() + " terminal-host=" + entry.exists()
            )
        }

        // 工作区按设置取值（DataStore workspacePath，默认 filesDir/workspace 兼容存量）：
        // 启动时取一次值，进程生命周期内固定——切换工作区只写 DataStore、不重启 DSH，
        // 只影响新会话（归属由首条消息定死），生成中的会话不受影响（plan-standard-mode §4.6）
        val workspaceDirFile = RuntimeExtractor.workspaceDir(context)
        val workspaceDir = workspaceDirFile.absolutePath
        val uploadsDir = RuntimeExtractor.workspaceUploadsDir(context).absolutePath
        val command = listOf(
            "/system/bin/linker64",
            node.absolutePath,
            entry.absolutePath,
        )
        Log.i(TAG, "launch: " + command.joinToString(" "))

        val pb = ProcessBuilder(command)
        pb.directory(workspaceDirFile)
        pb.environment().apply {
            // PATH：runtime/bin 里有 node/bash；/system/bin 提供 sh 等系统命令
            put("PATH", runtimeDir.absolutePath + "/bin:/system/bin:/system/xbin")
            put("HOME", workspaceDir)
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
            // bin/node、bin/bash wrapper 经它定位 lib/ 真 ELF（$HOME fallback 供 Termux 手测）
            put("MEOW_RUNTIME_DIR", runtimeDir.absolutePath)
            // PATH 命中 node/bash 时走 bash 导出函数（bash 启动自动导入 BASH_FUNC_*）：untrusted_app 域
            // SELinux 禁 exec app 数据文件，bin/* wrapper 脚本同样 EACCES（实测 bad interpreter:
            // Permission denied）；函数体不用 exec（会替换当前 shell），fork 子进程跑 linker64 → lib 真 ELF。
            // PTY bash 与 DSH bash 工具两条链路都经 env 继承拿到（scrub 只滤 KEY/PASSWORD/SECRET/TOKEN 与 DSH_ 前缀）。
            put(
                "BASH_FUNC_node%%",
                "() { /system/bin/linker64 \"\$MEOW_RUNTIME_DIR/lib/node.bin\" \"\$@\"; }",
            )
            put(
                "BASH_FUNC_bash%%",
                "() { /system/bin/linker64 \"\$MEOW_RUNTIME_DIR/lib/bash.bin\" \"\$@\"; }",
            )
            // node 真 ELF 绝对路径（terminal-host 的 launchDsh 用 linker64 加载它启动 DSH；linker64 下 process.execPath 会指向 linker64）
            put("DSH_NODE_BIN", node.absolutePath)
            // bash 二进制绝对路径（terminal-host 用 linker64 加载；bin/bash 是 wrapper 脚本）
            put("DSH_BASH_BIN", runtimeDir.absolutePath + "/lib/bash.bin")
            // DSH 会话持久化（SQLite）与默认 cwd（cordis.yml 里经 DSH_SESSION_DB / DSH_CWD 读取）
            put("DSH_SESSION_DB", context.filesDir.absolutePath + "/.dsh-sessions/chat.db")
            // 可配置 provider 的 settings / credentials 文档路径（模型管理，M4）
            // phase4：settings 迁至 appconfig/ 且 JSON 化（DSH settings-file 原生支持 .json）
            put("DSH_SETTINGS_PATH", RuntimeExtractor.appConfigDir(context).absolutePath + "/dsh-settings.json")
            put("DSH_CREDENTIALS_PATH", RuntimeExtractor.appConfigDir(context).absolutePath + "/dsh-credentials.yaml")
            put("DSH_UPLOAD_DIR", uploadsDir)
            put("DSH_CWD", workspaceDir)
            // DSH_HOME：用户预设根（${DSH_HOME}/.agent-presets/，全在私有目录天然可写）与 skills 发现根的基准
            // （此前未注入，回落到 workspace/.dsh；本计划显式固定到 filesDir/.dsh，plan-standard-mode §4.6）
            put("DSH_HOME", context.filesDir.absolutePath + "/.dsh")
            // filesDir 绝对路径（cordis.yml 的 fs-local deny 用绝对路径构造敏感文件规则）
            put("DSH_FILES_DIR", context.filesDir.absolutePath)
            // 网络搜索开关（'1' 启用；cordis.yml 里 tool-web.search 据此决定是否注册 web_search）
            put("DSH_WEB_SEARCH", if (webSearchEnabled) "1" else "0")
            if (apiKey.isNotBlank()) {
                put("DEEPSEEK_API_KEY", apiKey)
            }
        }
        return pb.start()
    }
}
