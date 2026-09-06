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

    /** 内置 apt 前缀（相对 filesDir）：复刻 Termux 前缀形状，deb 内硬编码路径经 dpkg --instdir 拼接落此
     *  （plan-apt-package-manager.md §3.2：物理 $PREFIX = <filesDir>/data/data/com.termux/files/usr） */
    private const val APT_PREFIX_REL = "data/data/com.termux/files/usr"

    /** apt 镜像（沿用主人现用镜像；备选官方 packages.termux.dev，风险 #10） */
    private const val APT_MIRROR = "https://termux.3san.dev/termux/termux-main"

    /**
     * 确保内置 apt 包管理器的运行环境（0.2.10，plan-apt-package-manager.md §4.2）。
     *
     * 幂等播种 App 可变区的 apt 骨架（升级/重启不洗已装包）：
     *  - 目录树 + dpkg 空库 status（仅在缺失时创建，禁覆盖已装包数据库）；
     *  - apt.conf（模板即代码，每次启动重写——升级即生效；镜像定制后续从设置读）；
     *  - sources.list。
     *
     * keyring 与 CA 不进可变区：trustedparts / SSL_CERT_FILE 直接指 runtime 只读区
     * （build-runtime.sh 打包时拷入），App 升级随 runtime 一起换新。
     *
     * @return 物理 $PREFIX（供 env 注入）
     */
    private fun ensureAptEnv(context: Context, runtimeDir: File): File {
        val prefix = File(context.filesDir, APT_PREFIX_REL)
        val p = prefix.absolutePath
        val rt = runtimeDir.absolutePath

        // 目录骨架（mkdirs 幂等）
        listOf(
            "bin", "lib", "tmp",
            "etc/apt/apt.conf.d", "etc/apt/sources.list.d", "etc/apt/preferences.d", "etc/apt/trusted.gpg.d",
            "var/lib/dpkg/info", "var/lib/dpkg/updates", "var/lib/dpkg/parts",
            "var/lib/dpkg/alternatives", "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial", "var/log/apt",
        ).forEach { File(prefix, it).mkdirs() }

        // dpkg 空库 status：只在缺失时创建（禁覆盖已装包数据库，风险 #5）
        val status = File(prefix, "var/lib/dpkg/status")
        if (!status.exists()) status.writeText("")

        // apt.conf：绝对路径全覆盖，防编译期 Termux 前缀漏网（apt/keyring/CA 路径全是编译期硬编码）
        File(prefix, "etc/apt/apt.conf").writeText(
            """
            Dir "$p/";
            Dir::State "$p/var/lib/apt/";
            Dir::State::status "$p/var/lib/dpkg/status";
            Dir::State::lists "$p/var/lib/apt/lists/";
            Dir::Cache "$p/var/cache/apt/";
            Dir::Cache::archives "$p/var/cache/apt/archives/";
            Dir::Etc "$p/etc/apt/";
            Dir::Etc::sourcelist "$p/etc/apt/sources.list";
            Dir::Etc::sourceparts "$p/etc/apt/sources.list.d/";
            Dir::Etc::trustedparts "$rt/usr/etc/apt/trusted.gpg.d/";
            Dir::Etc::main "$p/etc/apt/apt.conf";
            Dir::Etc::parts "$p/etc/apt/apt.conf.d/";
            Dir::Log "$p/var/log/apt";
            Dir::Bin::methods "$rt/usr/lib/apt/methods";
            Dir::Bin::dpkg "$rt/usr/bin/dpkg";
            Dir::Bin::gpg "$rt/usr/bin/gpgv";
            Dir::Bin::gpgv "$rt/usr/bin/gpgv";
            Dir::Bin::apt-key "$rt/usr/bin/apt-key";
            // apt 会给 dpkg 子进程设置硬编码 Termux PATH（DPkg::Path），必须改指喵仓 runtime；
            // 冒号分隔多个目录，dpkg 的 sh/rm 用 /system/bin，tar/diff/dpkg-* 用 runtime/usr/bin
            DPkg::Path "$rt/usr/bin:/system/bin";
            DPkg::Options {
              "--admindir=$p/var/lib/dpkg";
              "--instdir=${context.filesDir.absolutePath}";
            };
            Acquire::https::CAInfo "$rt/etc/tls/cert.pem";
            Acquire::Languages "none";
            """.trimIndent() + "\n",
        )

        // sources.list：镜像沿用（模板即代码，每次重写）
        File(prefix, "etc/apt/sources.list").writeText("deb $APT_MIRROR stable main\n")

        return prefix
    }


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
            // ── 内置 apt 包管理器环境（0.2.10 包管理器，plan-apt-package-manager.md §4.3）──
            // 物理 $PREFIX（可变区，装包落点）+ meow-exec exec 转发 + APT_CONFIG/TMPDIR/SSL_CERT_FILE。
            // PATH 前置 $PREFIX/bin：装出的命令优先命中（ELF 经 meow-exec 转 linker64，脚本转 sh）。
            // LD_PRELOAD 全链继承：apt→dpkg→postinst→装出的命令（childEnv 只滤 KEY/PASSWORD/SECRET/
            // TOKEN 与 DSH_ 前缀，不滤 LD_PRELOAD，DSH bash 工具链自动继承 ✓）。
            val aptPrefix = ensureAptEnv(context, runtimeDir)
            put("MEOW_PREFIX", aptPrefix.absolutePath)
            put("PREFIX", aptPrefix.absolutePath) // 供脚本里 $PREFIX 语义一致
            // file 等装出的工具编译期硬编码 Termux 前缀（/data/data/com.termux/files/usr/...），
            // App 域读不到；用 MAGIC 环境变量把 magic 数据库指到物理 $PREFIX 内（plan §七/阶段1已知坑）
            put("MAGIC", aptPrefix.absolutePath + "/share/misc/magic")
            put("APT_CONFIG", aptPrefix.absolutePath + "/etc/apt/apt.conf")
            put("TMPDIR", aptPrefix.absolutePath + "/tmp")
            put("SSL_CERT_FILE", runtimeDir.absolutePath + "/etc/tls/cert.pem") // openssl 编译期 CA 指 Termux，App 域环境变量接管
            put("CURL_CA_BUNDLE", runtimeDir.absolutePath + "/etc/tls/cert.pem") // libcurl 走 CURL_CA_BUNDLE，apt 的 https 方法才认
            put("LD_PRELOAD", runtimeDir.absolutePath + "/lib/meow-exec.so")
            // PATH：$PREFIX/bin（装出的命令）+ runtime/usr/bin（apt/dpkg/gpgv 本体）
            //        + runtime/bin（node/bash wrapper）+ 系统
            put("PATH", aptPrefix.absolutePath + "/bin:" + runtimeDir.absolutePath + "/usr/bin:" + runtimeDir.absolutePath + "/bin:/system/bin:/system/xbin")
            // LD_LIBRARY_PATH：装出包的新依赖优先，runtime 内置 .so 兜底
            put("LD_LIBRARY_PATH", aptPrefix.absolutePath + "/lib:" + runtimeDir.absolutePath + "/lib")
            put("HOME", workspaceDir)
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
