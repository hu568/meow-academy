package com.meow.academy.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.meow.academy.MeowAcademyApp
import com.meow.academy.data.settings.ResidentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前后台生命周期 → 常驻三档策略（M2.6，决策 v2 §2.4）。
 *
 * - 回前台：总是拉起 pi（聊天/终端需要）；
 * - 退后台：按档位决定——①关闭立即停、②有限保活延迟 N 分钟停、③一直常驻不停。
 */
class AppLifecycleObserver(
    private val app: MeowAcademyApp,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        Log.i("AppLifecycle", "timed stop: dsh 停止")
        app.runtimeManager.stop()
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(stopRunnable)
        scope.launch {
            // 前台使用中必然要 DSH；start() 幂等
            app.runtimeManager.start()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            val mode = app.settingsRepository.residentMode.first()
            Log.i("AppLifecycle", "background, residentMode=$mode")
            when (mode) {
                ResidentMode.OFF -> app.runtimeManager.stop()
                ResidentMode.TIMED -> {
                    val minutes = app.settingsRepository.residentMinutes.first().coerceIn(1, 1440)
                    handler.postDelayed(stopRunnable, minutes * 60_000L)
                }
                ResidentMode.ALWAYS -> Unit // 保持常驻
            }
        }
    }

    /** 挂载到 ProcessLifecycleOwner */
    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
}