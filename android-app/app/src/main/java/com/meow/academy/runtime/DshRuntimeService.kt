package com.meow.academy.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meow.academy.MainActivity
import com.meow.academy.MeowAcademyApp
import com.meow.academy.R
import com.meow.academy.rpc.DshRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * DSH 运行时前台服务（替代 pi 时代的 PiRuntimeService）。
 *
 * 持有 DSH 进程与 [DshRpcClient]，通过低优先级常驻通知保活；
 * 三档常驻策略（M2.6）在此基础上控制通知/停止时机。
 */
class DshRuntimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var client: DshRpcClient? = null
    private var socket: LocalSocket? = null

    /** 启动互斥：startDsh 异步（先读配置），onStartCommand 并发时防止双进程 */
    private val launching = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDsh()
            return START_NOT_STICKY
        }
        if (process != null && process?.isAlive == true) {
            return START_NOT_STICKY // 已运行
        }
        if (launching.compareAndSet(false, true)) {
            startDsh()
        }
        return START_NOT_STICKY
    }

    /** 拉起 DSH 进程并创建 RPC 客户端（异步：需要先读配置） */
    private fun startDsh() {
        val app = application as MeowAcademyApp
        scope.launch {
            try {
                val settings = app.settingsRepository
                val providerRaw = settings.llmProvider.first()
                val model = settings.llmModel.first()
                val apiKey = settings.llmApiKey.first()

                if (apiKey.isBlank()) {
                    updateNotification("缺少 API Key")
                    Log.w("DshRuntimeService", "apiKey blank, skip launch")
                    stopSelf()
                    return@launch
                }

                // 设置里的 "deepseek" 映射到 DSH 的 provider 路由名 deepseek-official
                val provider = if (providerRaw == "deepseek") "deepseek-official" else providerRaw

                // 真终端 + 聊天 socket 路径（terminal-host 与 DSH 各自监听）
                val terminalSocket = java.io.File(app.filesDir, "dsh-terminal.sock").absolutePath
                val jsonRpcSocket = java.io.File(app.filesDir, "dsh-jsonrpc.sock").absolutePath
                // 清理旧 socket 文件（进程上次退出可能残留）
                java.io.File(terminalSocket).delete()
                java.io.File(jsonRpcSocket).delete()

                val proc = DshProcessLauncher.launch(this@DshRuntimeService, apiKey, terminalSocket, jsonRpcSocket)
                Log.i("DshRuntimeService", "launched proc alive=" + proc.isAlive)
                if (process != null) {
                    proc.destroyForcibly()
                    launching.set(false)
                    return@launch
                }
                process = proc

                // terminal-host 的 stderr 转发到 Logcat（DshStderr tag，排障）
                Thread {
                    try {
                        proc.errorStream.bufferedReader().forEachLine { line ->
                            Log.e("DshStderr", line)
                        }
                    } catch (_: Exception) {
                    }
                }.start()

                // 等 DSH 聊天 socket 就绪后连接（terminal-host 拉起 bash → bash 内启动 DSH 需要时间）
                val rpc = connectToDsh(jsonRpcSocket)
                if (rpc == null) {
                    proc.destroyForcibly()
                    throw IllegalStateException("DSH 聊天 socket 连接失败")
                }
                client = rpc
                app.runtimeManager.rpcClient = rpc
                Log.i("DshRuntimeService", "dsh socket connected, starting read loop")
                rpc.start()

                // JSON-RPC 握手：initialize 完成前会话请求会用错 model，必须先等它成功
                val initialized = rpc.initialize(
                    cwd = app.filesDir.absolutePath,
                    provider = provider,
                    model = model,
                )
                if (!initialized) {
                    Log.e("DshRuntimeService", "initialize failed, kill proc")
                    proc.destroyForcibly()
                    throw IllegalStateException("DSH initialize 失败")
                }

                app.runtimeManager.markRunning()
                updateNotification("运行中 · " + provider + "/" + model)
                Log.i("DshRuntimeService", "startDsh complete")
                launching.set(false)

                // 进程意外退出 → 收管道、清理状态、回落
                Thread {
                    val code = proc.waitFor()
                    Log.i("DshRuntimeService", "dsh exited code=" + code)
                    if (process === proc) {
                        process = null
                    }
                    if (client === rpc) {
                        rpc.close() // 收掉读循环与 pending，避免悬挂 collect / 断管写
                        client = null
                        app.runtimeManager.rpcClient = null
                    }
                    app.runtimeManager.markStopped()
                    stopSelf()
                }.start()
            } catch (e: Exception) {
                Log.e("DshRuntimeService", "start dsh failed", e)
                markLaunchFailed()
                updateNotification("启动失败：" + e.message)
                stopSelf()
            } finally {
                launching.set(false)
            }
        }
    }

    /** 轮询连接 DSH 聊天 unix socket（terminal-host 拉起 bash 后启动 DSH 需要时间） */
    private suspend fun connectToDsh(jsonRpcSocket: String): DshRpcClient? {
        repeat(40) { attempt ->
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(jsonRpcSocket, LocalSocketAddress.Namespace.FILESYSTEM))
                s.soTimeout = 0
                socket = s
                Log.i("DshRuntimeService", "dsh socket connected on attempt " + attempt)
                return DshRpcClient(
                    input = s.inputStream,
                    output = s.outputStream,
                    stderr = null,
                )
            } catch (e: Exception) {
                // 未就绪，稍后重试
            }
            delay(500)
        }
        return null
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("启动中…")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "喵学堂运行时",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "DeepSeek Harness 后台运行时（低优先级，不打扰）"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("喵学堂 · DSH 运行时")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** 启动失败时要求 RuntimeManager 置 Error 状态 */
    private fun markLaunchFailed() {
        (application as MeowAcademyApp).runtimeManager.markLaunchFailed()
    }

    override fun onDestroy() {
        Log.i("DshRuntimeService", "onDestroy")
        client?.close()
        client = null
        socket?.close()
        socket = null
        process?.destroyForcibly()
        process = null
        (application as? MeowAcademyApp)?.runtimeManager?.rpcClient = null
        scope.cancel()
        super.onDestroy()
    }

    /** 主动停止：杀进程、收管道（waitFor 线程会自行 stopSelf，这里不重复调） */
    private fun stopDsh() {
        Log.i("DshRuntimeService", "stopDsh")
        val rm = (application as MeowAcademyApp).runtimeManager
        client?.close()
        client = null
        socket?.close()
        socket = null
        process?.destroyForcibly()
        process = null
        rm.rpcClient = null
        rm.markStopped()
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.meow.academy.action.STOP_DSH"
        private const val CHANNEL_ID = "dsh_runtime"
        private const val NOTIFICATION_ID = 1001

        /** 便捷启动（确保前台服务） */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DshRuntimeService::class.java))
        }

        /** 便捷停止 */
        fun stop(context: Context) {
            context.startService(Intent(context, DshRuntimeService::class.java).setAction(ACTION_STOP))
        }
    }
}