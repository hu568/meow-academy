package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 在 Android 应用私有目录里直接执行内置 node（Termux 版静态链接二进制）。
 *
 * pi 是纯 JS CLI，入口为 node + dist/cli.js，无需 Termux 环境。
 * 关键点：
 *  - node 二进制位于 filesDir/meow-runtime/bin/node（自包含，见 M2.0 实测）；
 *  - 通过环境变量注入 API Key / HOME / PATH，不依赖 Termux 配置文件；
 *  - 工作目录设为 filesDir（作为 pi 的 home）。
 */
object PiProcessLauncher {

    private const val TAG = "PiProcessLauncher"

    /** pi CLI 相对 runtime 根目录的入口 */
    private const val CLI_REL = "lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js"

    /**
     * 拉起 pi：通过 `/system/bin/linker64` 加载内置 node（Android app 沙箱
     * 直接 exec 自解压 ELF 在某些 ROM 会被 EACCES 拒绝，linker 加载是标准允许路径）。
     *
     * @throws IOException 进程拉起失败
     */
    fun launch(
        context: Context,
        provider: String,
        model: String,
        apiKey: String,
    ): Process {
        val runtimeDir = RuntimeExtractor.runtimeDir(context)
        val node = File(runtimeDir, "bin/node")
        val cli = File(runtimeDir, CLI_REL)
        if (!node.exists() || !cli.exists()) {
            throw IOException("runtime 不完整：node=${node.exists()} cli=${cli.exists()}")
        }

        val command = listOf(
            "/system/bin/linker64",
            node.absolutePath,
            cli.absolutePath,
            "--mode", "rpc",
            "--no-session",
            "--provider", provider,
            "--model", model,
        )
        Log.i(TAG, "launch: ${command.joinToString(" ")}")

        val pb = ProcessBuilder(command)
        pb.directory(context.filesDir)
        pb.environment().apply {
            // PATH 需含 /system/bin（pi 的 bash 工具 fallback 到 `sh -c`，Android 的 sh 在 /system/bin）
            put("PATH", "${runtimeDir.absolutePath}/bin:/system/bin:/system/xbin")
            put("HOME", context.filesDir.absolutePath)
            // node 是动态链接 Termux 库（libz/libcrypto/libicu 等），需指向 runtime 内置的 .so
            put("LD_LIBRARY_PATH", "${runtimeDir.absolutePath}/lib")
            // Termux 版 node 的 OpenSSL 默认配置/CA 路径编译在 Termux 私有目录（App 沙箱无权限读），
            // 必须重定向到 runtime 内置的 openssl.cnf（空文件）+ cert.pem（CA 束），否则所有 TLS 请求失败
            put("OPENSSL_CONF", "${runtimeDir.absolutePath}/etc/tls/openssl.cnf")
            put("NODE_EXTRA_CA_CERTS", "${runtimeDir.absolutePath}/etc/tls/cert.pem")
            // DNS 兜底：App 沙箱内 node 的 getaddrinfo 可能无法走 netd 解析（ENOTFOUND），
            // shim 钩住 dns.lookup 失败时直连公共 DNS（见 runtime-assets/dns-shim.js）
            put("NODE_OPTIONS", "--require ${runtimeDir.absolutePath}/lib/dns-shim.js")
            if (apiKey.isNotBlank()) {
                put("DEEPSEEK_API_KEY", apiKey)
            }
        }
        return pb.start()
    }
}
