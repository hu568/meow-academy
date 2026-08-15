package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 在 Android 应用私有目录里直接执行内置 node（Termux 版动态链接二进制），
 * 入口为 DSH jsonrpc 打包运行时：node + packaged-bin.js + dsh/cordis.yml。
 *
 * 关键点：
 *  - node / bash 二进制位于 filesDir/meow-runtime/bin/（自包含，见 build-runtime.sh）；
 *  - DSH closure 位于 filesDir/meow-runtime/node_modules/（pnpm deploy 产物，相对 symlink 自洽）；
 *  - 通过环境变量注入 API Key / HOME / PATH / 会话与 cwd，不依赖 Termux 配置文件；
 *  - 工作目录设为 filesDir（DSH 的 cwd 与 DSH_SESSION_ROOT 的锚点）。
 */
object DshProcessLauncher {

    private const val TAG = "DshProcessLauncher"

    /** jsonrpc 运行时入口（相对 runtime 根目录） */
    private const val ENTRY_REL = "node_modules/@deepseek-ai/dsh-sdk-jsonrpc-demo/lib/packaged-bin.js"

    /** 喵学堂组合（cordis.yml） */
    private const val CONFIG_REL = "dsh/cordis.yml"

    /**
     * 拉起 DSH：通过 `/system/bin/linker64` 加载内置 node（Android app 沙箱
     * 直接 exec 自解压 ELF 在某些 ROM 会被 EACCES 拒绝，linker 加载是标准允许路径）。
     *
     * @throws IOException 进程拉起失败
     */
    fun launch(
        context: Context,
        apiKey: String,
    ): Process {
        val runtimeDir = RuntimeExtractor.runtimeDir(context)
        val node = File(runtimeDir, "bin/node")
        val entry = File(runtimeDir, ENTRY_REL)
        val config = File(runtimeDir, CONFIG_REL)
        if (!node.exists() || !entry.exists() || !config.exists()) {
            throw IOException(
                "runtime 不完整：node=" + node.exists() + " entry=" + entry.exists() +
                    " cordis.yml=" + config.exists()
            )
        }

        val command = listOf(
            "/system/bin/linker64",
            node.absolutePath,
            entry.absolutePath,
            config.absolutePath,
        )
        Log.i(TAG, "launch: " + command.joinToString(" "))

        val pb = ProcessBuilder(command)
        pb.directory(context.filesDir)
        pb.environment().apply {
            // PATH：runtime/bin 里有 node/bash；/system/bin 提供 sh 等系统命令（旧坑：pi 时代必需，DSH 同需）
            put("PATH", runtimeDir.absolutePath + "/bin:/system/bin:/system/xbin")
            put("HOME", context.filesDir.absolutePath)
            // node/bash 是动态链接 Termux 库（libz/libcrypto/libicu 等），需指向 runtime 内置的 .so
            put("LD_LIBRARY_PATH", runtimeDir.absolutePath + "/lib")
            // Termux 版 node 的 OpenSSL 默认配置/CA 路径编译在 Termux 私有目录（App 沙箱无权限读），
            // 必须重定向到 runtime 内置的 openssl.cnf（空文件）+ cert.pem（CA 束），否则所有 TLS 请求失败
            put("OPENSSL_CONF", runtimeDir.absolutePath + "/etc/tls/openssl.cnf")
            put("NODE_EXTRA_CA_CERTS", runtimeDir.absolutePath + "/etc/tls/cert.pem")
            // DNS 兜底：App 沙箱内 node 的 getaddrinfo 可能无法走 netd 解析（ENOTFOUND），
            // shim 钩住 dns.lookup 失败时直连公共 DNS（见 runtime-assets/dns-shim.js）
            put("NODE_OPTIONS", "--require " + runtimeDir.absolutePath + "/lib/dns-shim.js")
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
