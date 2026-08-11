package com.meow.academy.rpc

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * JSONL 帧解析：把 InputStream 按 `\n` 分割成行（容错 `\r\n` 与末尾无换行）。
 *
 * 每行作为一个字符串发射，由调用方解析为 JSON。pi RPC 文档明确要求：
 * 只按 LF 分割、剥离尾部 CR、不处理其他空白。
 */
class JsonlFrameReader(private val input: InputStream) {

    fun lines(): Flow<String> = callbackFlow {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        try {
            // 逐行读取；BufferedReader 天然按 \n 分割，行尾 \r 需要剥离
            while (true) {
                val line = reader.readLine() ?: break
                // 挂起 send：消费者（RPC 读循环）来不及消费时反压而非丢行
                send(if (line.endsWith("\r")) line.dropLast(1) else line)
            }
        } catch (e: Exception) {
            close(e)
        } finally {
            close()
        }
        awaitClose { runCatching { reader.close() } }
    }
}
