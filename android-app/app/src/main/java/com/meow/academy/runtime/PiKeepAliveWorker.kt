package com.meow.academy.runtime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.settings.ResidentMode
import com.meow.academy.rpc.RpcCommand
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Pi 心跳守护（M2.6）：周期检查 pi 是否存活，异常退出自动重启。
 *
 * 档位为 OFF 时自我取消（不守护）；②有限 / ③一直 档位下工作。
 * 存活检测用 RPC `get_state`（进程活着且可响应即视为健康）。
 */
class PiKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MeowAcademyApp
        val mode = app.settingsRepository.residentMode.first()
        Log.i("PiKeepAlive", "heartbeat, mode=$mode")

        if (mode == ResidentMode.OFF) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(UNIQUE_WORK)
            return Result.success()
        }

        val rpc = app.runtimeManager.rpcClient
        if (rpc != null) {
            val resp = runCatching {
                rpc.send(RpcCommand(type = "get_state"), timeoutMs = 8_000)
            }.getOrNull()
            if (resp?.success == true) {
                Log.i("PiKeepAlive", "pi alive")
                return Result.success()
            }
        }
        // 未运行或不可达 → 重新拉起（幂等）
        Log.i("PiKeepAlive", "pi not alive, restarting")
        app.runtimeManager.start()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK = "pi-keepalive"

        /** 调度周期心跳（App 启动时调用；档位判断在 worker 内） */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PiKeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
