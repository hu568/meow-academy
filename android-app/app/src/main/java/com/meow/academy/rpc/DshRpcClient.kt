package com.meow.academy.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
sealed interface DshConnectionState {
    data object Connecting : DshConnectionState
    data object Running : DshConnectionState
    data class Closed(val error: String? = null) : DshConnectionState
}

/**
 * DSH jsonrpc 协议客户端（JSON-RPC 2.0 over stdio）。
 *
 * 职责：
 *  - 读取 DSH stdout 的 JSONL 流：带 id 的帧按 id 路由给调用方，其余作为通知广播；
 *  - 向 stdin 写入请求（Mutex 串行化，防交错）；
 *  - 暴露 [events] 供聊天页/终端页订阅（按 sessionId / requestId 过滤）；
 *  - [initialize] 在连接建立后调用一次（进程级握手：cwd/provider/model）。
 *
 * 进程生命周期由外部（DshRuntimeService）管理，本类只负责管道。
 */
class DshRpcClient(
    private val input: java.io.InputStream,
    private val output: OutputStream,
    private val stderr: java.io.InputStream? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _events = MutableSharedFlow<DshEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<DshEvent> = _events

    private val _state = MutableStateFlow<DshConnectionState>(DshConnectionState.Connecting)
    val state: StateFlow<DshConnectionState> = _state

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<DshResponse>>()
    private val writeMutex = Mutex()
    private var readJob: Job? = null
    private var stderrJob: Job? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 启动读循环（stdout 帧路由 + stderr 日志）。在进程拉起后调用一次。 */
    fun start() {
        readJob = scope.launch {
            _state.value = DshConnectionState.Running
            try {
                JsonlFrameReader(input).lines().collect { line ->
                    try {
                        val element = json.parseToJsonElement(line)
                        if (element is JsonObject) dispatch(element)
                    } catch (e: Exception) {
                        // 单帧解析失败不影响后续
                        android.util.Log.w("DshRpcClient", "bad frame: ${line.take(120)}", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DshRpcClient", "read loop failed", e)
            } finally {
                // 读循环结束 = 管道断开（EOF/进程退出/被 close）：
                // 标记关闭并立刻失败所有 pending，避免调用方干等超时
                closed.set(true)
                pending.values.forEach { it.completeExceptionally(CancellationException("pipe closed")) }
                pending.clear()
                _state.value = DshConnectionState.Closed(null)
            }
        }
        // stderr 只是日志（仅 stdio 模式转发到 Logcat；socket 模式下 DSH stderr 走真终端 PTY）
        stderrJob = stderr?.let { err ->
            scope.launch {
                JsonlFrameReader(err).lines().collect { line ->
                    android.util.Log.d("DshStderr", line)
                }
            }
        }
    }

    /** run 在 read loop（suspend 上下文）；事件用挂起 [kotlinx.coroutines.flow.emit] 保证不丢 */
    private suspend fun dispatch(obj: JsonObject) {
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        android.util.Log.i("DshRpcClient", "recv: method=$method id=${id?.take(12)} ${obj.toString().take(600)}")

        if (id != null) {
            // 响应帧（我们不会收到带 method 的请求帧）
            val resp = runCatching {
                json.decodeFromString<DshResponse>(json.encodeToString(obj))
            }.getOrNull() ?: return
            pending.remove(id)?.complete(resp)
            return
        }
        if (method != null) {
            // 通知帧 → 广播（挂起写，缓冲满时反压而非丢帧）
            _events.emit(DshEvent.from(obj))
        }
    }

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * @return null = 超时/管道断开；非 null 时检查 [DshResponse.ok]（error 帧也有值）
     */
    suspend fun request(method: String, params: JsonObject? = null, timeoutMs: Long = 30_000): DshResponse? {
        val id = newId()
        val frame = DshRequest(id = id, method = method, params = params)
        val deferred = kotlinx.coroutines.CompletableDeferred<DshResponse>()
        pending[id] = deferred
        try {
            write(frame)
            // 管道已断（进程刚退出）：立刻失败，不要让调用方干等超时
            if (closed.get()) {
                deferred.completeExceptionally(CancellationException("pipe closed"))
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** 请求成功（有响应且无 error 字段） */
    suspend fun requestOk(method: String, params: JsonObject? = null, timeoutMs: Long = 30_000): Boolean =
        request(method, params, timeoutMs)?.ok == true

    // ── 协议方法的便捷封装 ──

    /** initialize：进程级握手。必须在会话请求前完成（server 默认 model 非合法模型） */
    suspend fun initialize(
        cwd: String,
        provider: String,
        model: String,
        reasoningEffort: String? = null,
        timeoutMs: Long = 20_000,
    ): Boolean = requestOk("initialize", DshParams.initialize(cwd, provider, model, reasoningEffort = reasoningEffort), timeoutMs)

    /** session/prompt：入队一条消息（响应是受理确认，事件走 session.event） */
    suspend fun prompt(sessionId: String, text: String, timeoutMs: Long = 15_000): Boolean =
        requestOk("session/prompt", DshParams.prompt(sessionId, text), timeoutMs)

    /** session/cancel：停止生成 */
    suspend fun cancelSession(sessionId: String, timeoutMs: Long = 10_000): Boolean =
        requestOk("session/cancel", DshParams.cancel(sessionId), timeoutMs)

    /** session/setModel：运行时切换某会话的模型/思考强度（只更新传入字段） */
    suspend fun setModel(
        sessionId: String,
        provider: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        timeoutMs: Long = 10_000,
    ): Boolean = requestOk("session/setModel", DshParams.setModel(sessionId, provider, model, reasoningEffort), timeoutMs)

    /** session/bash：执行终端命令；返回最终结果（status/exitCode/timedOut/cancelled） */
    suspend fun bash(
        requestId: String,
        command: String,
        workdir: String? = null,
        timeoutMs: Long? = null,
        awaitTimeoutMs: Long = 300_000,
    ): JsonObject? = request("session/bash", DshParams.bash(requestId, command, workdir, timeoutMs), awaitTimeoutMs)?.result

    /** session/bashCancel：中止终端命令 */
    suspend fun bashCancel(requestId: String, timeoutMs: Long = 10_000): Boolean =
        requestOk("session/bashCancel", DshParams.bashCancel(requestId), timeoutMs)

    /** ping：心跳（保活 worker 用；进程活着且可响应即 true） */
    suspend fun ping(timeoutMs: Long = 8_000): Boolean =
        requestOk("ping", null, timeoutMs)

    private suspend fun write(frame: DshRequest) {
        writeMutex.withLock {
            if (closed.get()) {
                android.util.Log.w("DshRpcClient", "write on closed client, drop: ${frame.method}")
                return
            }
            val line = json.encodeToString(frame) + "\n"
            try {
                android.util.Log.i("DshRpcClient", "write: ${line.trim().take(140)}")
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
                android.util.Log.i("DshRpcClient", "write flushed")
            } catch (e: Exception) {
                // 断管（进程已退出）：标记关闭并静默吞掉
                android.util.Log.e("DshRpcClient", "write failed (pipe broken), mark closed", e)
                closed.set(true)
            }
        }
    }

    /** 生成请求 id */
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
