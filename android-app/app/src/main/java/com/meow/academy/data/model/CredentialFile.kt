package com.meow.academy.data.model

import java.io.File

/**
 * DSH credentials-local 文档的轻量读取（仅用于本地回显缓存迁移）。
 *
 * DSH 把自定义 provider 的 API Key 明文写在 `$DSH_CREDENTIALS_PATH`
 * （Android 上为 `filesDir/appconfig/dsh-credentials.yaml`，phase4 目录重构后），
 * 格式是简单的 `CredentialRef: value` 映射。这里不做完整 YAML 解析，只覆盖
 * 喵仓/DSH 写出的常规标量形态；解析失败时静默返回空，仅影响旧 Key 回显缓存迁移。
 */

/** 与 meow-jsonrpc.js 的 providerCredentialRef 保持一致 */
fun providerCredentialRef(provider: String): String =
    "MEOW_" + provider.replace(Regex("[^A-Za-z0-9]"), "_").uppercase() + "_API_KEY"

/** 读取 DSH credentials document，返回 CredentialRef → 明文值 */
fun readDshCredentialsFile(file: File): Map<String, String> {
    if (!file.isFile || !file.canRead()) return emptyMap()
    val result = mutableMapOf<String, String>()
    runCatching {
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            if (line == "---" || line == "..." || line.startsWith("--- ") || line.startsWith("... ")) return@forEachLine

            val colon = line.indexOf(':')
            if (colon <= 0) return@forEachLine
            val key = line.substring(0, colon).trim()
            if (key.isEmpty()) return@forEachLine
            var value = line.substring(colon + 1).trim()

            // 解开 YAML 单/双引号标量
            value = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value.substring(1, value.length - 1).replace("''", "'")
            } else {
                // 未加引号的标量允许行尾注释
                val hash = value.indexOf(" #")
                if (hash >= 0) value = value.substring(0, hash).trim()
                value
            }

            if (value.isNotEmpty() && value != "null" && value != "~") {
                result[key] = value
            }
        }
    }
    return result
}