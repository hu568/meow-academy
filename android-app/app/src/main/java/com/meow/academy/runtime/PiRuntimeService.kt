package com.meow.academy.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meow.academy.MainActivity
import com.meow.academy.MeowAcademyApp
import com.meow.academy.R
import com.meow.academy.rpc.PiRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pi 运行时前台服务（M2.2）。
 *
 * 持有 pi 进程与 [PiRpcClient]，通过低优先级常驻通知保活；
 * 三档常驻策略（M2.6）在此基础上控制通知/停止时机。
 */
class PiRuntimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var client: PiRpcClient? = null

    /** 启动互斥：startPi 异步（先读配置），onStartCommand 并发时防止双进程 */
    private val launching = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPi()
            return START_NOT_STICKY
        }
        if (process != null && process?.isAlive == true) {
            return START_NOT_STICKY // 已运行
        }
        if (launching.compareAndSet(false, true)) {
            startPi()
        }
        return START_NOT_STICKY
    }

    /** 拉起 pi 进程并创建 RPC 客户端（异步：需要先读配置） */
    private fun startPi() {
        val app = application as MeowAcademyApp
        scope.launch {
            try {
                val settings = app.settingsRepository
                val provider = settings.llmProvider.first()
                val model = settings.llmModel.first()
                val apiKey = settings.llmApiKey.first()

                if (apiKey.isBlank()) {
                    updateNotification("缺少 API Key")
                    Log.w("PiRuntimeService", "apiKey blank, skip launch")
                    stopSelf()
                    return@launch
                }

                val proc = PiProcessLauncher.launch(this@PiRuntimeService, provider, model, apiKey)
                Log.i("PiRuntimeService", "launched proc alive=${proc.isAlive}")
                if (process != null) {
                    // 竞态兜底：本服务不应有第二个存活进程（launching 已互斥，防御性检查）
                    proc.destroyForcibly()
                    launching.set(false)
                    return@launch
                }
                process = proc
                val rpc = PiRpcClient(
                    stdout = proc.inputStream,
                    stderr = proc.errorStream,
                    stdin = proc.outputStream,
                )
                client = rpc
                // 暴露给 RuntimeManager（App 持有）
                app.runtimeManager.rpcClient = rpc
                Log.i("PiRuntimeService", "rpc client created, starting read loop")
                rpc.start()
                app.runtimeManager.markRunning()
                updateNotification("运行中 · $provider/$model")
                Log.i("PiRuntimeService", "startPi complete")
                launching.set(false)

                // 进程意外退出 → 收管道、清理状态、回落
                Thread {
                    val code = proc.waitFor()
                    Log.i("PiRuntimeService", "pi exited code=$code")
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
                Log.e("PiRuntimeService", "start pi failed", e)
                markLaunchFailed()
                updateNotification("启动失败：${e.message}")
                stopSelf()
            } finally {
                launching.set(false)
            }
        }
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
            description = "Pi Agent 后台运行时（低优先级，不打扰）"
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
            .setContentTitle("喵学堂 · Pi 运行时")
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
        Log.i("PiRuntimeService", "onDestroy")
        client?.close()
        client = null
        process?.destroyForcibly()
        process = null
        (application as? MeowAcademyApp)?.runtimeManager?.rpcClient = null
        scope.cancel()
        super.onDestroy()
    }

    /** 主动停止：杀进程、收管道（waitFor 线程会自行 stopSelf，这里不重复调） */
    private fun stopPi() {
        Log.i("PiRuntimeService", "stopPi")
        val rm = (application as MeowAcademyApp).runtimeManager
        client?.close()
        client = null
        process?.destroyForcibly()
        process = null
        rm.rpcClient = null
        rm.markStopped()
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.meow.academy.action.STOP_PI"
        private const val CHANNEL_ID = "pi_runtime"
        private const val NOTIFICATION_ID = 1001

        /** 便捷启动（确保前台服务） */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PiRuntimeService::class.java))
        }

        /** 便捷停止 */
        fun stop(context: Context) {
            context.startService(Intent(context, PiRuntimeService::class.java).setAction(ACTION_STOP))
        }
    }
}
