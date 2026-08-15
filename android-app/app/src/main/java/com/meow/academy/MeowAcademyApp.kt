package com.meow.academy

import android.app.Application
import androidx.work.WorkManager
import com.meow.academy.data.chat.ChatDatabase
import com.meow.academy.data.settings.ResidentMode
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.runtime.AppLifecycleObserver
import com.meow.academy.runtime.DshKeepAliveWorker
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 喵学堂 Application：持有全局单例（设置仓库 / 运行时管理器等） */
class MeowAcademyApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val runtimeManager: RuntimeManager by lazy { RuntimeManager(this, settingsRepository) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 前后台档位策略（M2.6）
        AppLifecycleObserver(this, appScope).install()
        // 心跳守护：跟随档位变化同步调度（OFF 取消；切回 ②/③ 立即恢复，无需重启 App）
        appScope.launch {
            settingsRepository.residentMode.collect { mode ->
                if (mode == ResidentMode.OFF) {
                    WorkManager.getInstance(this@MeowAcademyApp).cancelUniqueWork(DshKeepAliveWorker.UNIQUE_WORK)
                } else {
                    DshKeepAliveWorker.schedule(this@MeowAcademyApp)
                }
            }
        }
        // 兜底：进程中途被杀后残留的 STREAMING 消息标记为 ERROR，避免永远「思考中…」
        appScope.launch {
            ChatDatabase.get(this@MeowAcademyApp).chatDao().cleanupStaleStreaming()
        }
    }
}