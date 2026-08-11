package com.meow.academy.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** 连接状态 */
sealed interface PiConnectionState {
    data object Connecting : PiConnectionState
    data object Running : PiConnectionState
    data class Closed(val error: String? = null) : PiConnectionState
}

/**
 * pi RPC 协议客户端。
 *
 * 职责：
 *  - 读取 pi stdout 的 JSONL 流，把 `response` 按 id 路由给调用方、其余作为事件广播；
 *  - 向 pi stdin 写入命令（Mutex 串行化，防交错）；
 *  - 暴露 [events] 供聊天页/终端页订阅。
 *
 * 进程生命周期由外部（PiRuntimeService）管理，本类只负责管道。
 */
class PiRpcClient(
    private val stdout: java.io.InputStream,
    private val stderr: java.io.InputStream,
    private val stdin: OutputStream,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableSharedFlow<PiEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PiEvent> = _events

    private val _state = MutableStateFlow<PiConnectionState>(PiConnectionState.Connecting)
    val state: StateFlow<PiConnectionState> = _state

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<PiResponse>>()
    private val writeMutex = Mutex()
    private var readJob: Job? = null
    private var stderrJob: Job? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 启动读循环（stdout 事件 + stderr 日志）。在进程拉起后调用一次。 */
    fun start() {
        readJob = scope.launch {
            _state.value = PiConnectionState.Running
            try {
                JsonlFrameReader(stdout).lines().collect { line ->
                    try {
                        val element = json.parseToJsonElement(line)
                        if (element is JsonObject) {
                            dispatch(element)
                        }
                    } catch (e: Exception) {
                        // 单帧解析失败不影响后续（pi 一般不会发非 JSON 行）
                        android.util.Log.w("PiRpcClient", "bad frame: ${line.take(120)}", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PiRpcClient", "read loop failed", e)
            } finally {
                // 读循环结束 = 管道断开（EOF/进程退出/被 close）：
                // 标记关闭并立刻失败所有 pending，避免调用方干等 send 超时
                closed.set(true)
                pending.values.forEach { it.completeExceptionally(CancellationException("pipe closed")) }
                pending.clear()
                _state.value = PiConnectionState.Closed(null)
            }
        }
        // stderr 只是日志，转发到 Logcat 便于排障
        stderrJob = scope.launch {
            JsonlFrameReader(stderr).lines().collect { line ->
                android.util.Log.d("PiStderr", line)
            }
        }
    }

    /** run 在 read loop（suspend 上下文）；事件用挂起 [emit] 保证不丢 */
    private suspend fun dispatch(obj: JsonObject) {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        android.util.Log.i("PiRpcClient", "recv: type=$type ${obj.toString().take(150)}")
        if (type == "response") {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val resp = runCatching {
                json.decodeFromString<PiResponse>(Json.encodeToString(obj))
            }.getOrNull()
            if (resp != null) {
                if (id != null) {
                    pending.remove(id)?.complete(resp)
                }
                // 无 id 的 response（fire-and-forget 确认）直接丢弃
            }
            return
        }
        // 事件广播（挂起写，缓冲满时反压而非丢帧）
        _events.emit(PiEvent(obj))
    }

    /**
     * 发送命令并等待对应 response。
     *
     * @param timeoutMs 等待响应超时；prompt 的 response 是「受理确认」（很快），
     *                  bash 的 response 在命令执行完后才发（可能很久），按需传参。
     */
    suspend fun send(cmd: RpcCommand, timeoutMs: Long = 30_000): PiResponse? {
        val id = cmd.id ?: UUID.randomUUID().toString()
        val withId = cmd.copy(id = id)
        val deferred = kotlinx.coroutines.CompletableDeferred<PiResponse>()
        pending[id] = deferred
        try {
            write(withId)
            // 管道已断（进程刚退出）：立刻失败，不要让调用方干等超时
            if (closed.get()) {
                deferred.completeExceptionally(CancellationException("pipe closed"))
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** 发送命令但不等待响应（abort / new_session / get_state 等） */
    suspend fun sendFireAndForget(cmd: RpcCommand) {
        write(cmd)
    }

    private suspend fun write(cmd: RpcCommand) {
        writeMutex.withLock {
            if (closed.get()) {
                android.util.Log.w("PiRpcClient", "write on closed client, drop: ${cmd.type}")
                return
            }
            val line = json.encodeToString(cmd) + "\n"
            try {
                android.util.Log.i("PiRpcClient", "write: ${line.trim().take(120)}")
                stdin.write(line.toByteArray(Charsets.UTF_8))
                stdin.flush()
                android.util.Log.i("PiRpcClient", "write flushed")
            } catch (e: Exception) {
                // 断管（pi 进程已退出）：标记关闭并静默吞掉，
                // 调用方（含 fire-and-forget）不应因进程退出而崩
                android.util.Log.e("PiRpcClient", "write failed (pipe broken), mark closed", e)
                closed.set(true)
            }
        }
    }

    /** 生成带 id 的命令（用于需要关联 response 的场景） */
    fun newId(): String = UUID.randomUUID().toString()

    /** 关闭客户端（不杀进程，进程由服务关） */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(CancellationException("client closed")) }
        pending.clear()
        readJob?.cancel()
        stderrJob?.cancel()
    }
}
