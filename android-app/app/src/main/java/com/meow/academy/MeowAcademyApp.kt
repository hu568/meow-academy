package com.meow.academy

import android.app.Application
import androidx.work.WorkManager
import com.meow.academy.data.chat.ChatDatabase
import com.meow.academy.data.model.ModelCatalogRepository
import com.meow.academy.data.settings.MarkdownConfigRepository
import com.meow.academy.data.settings.ResidentMode
import com.meow.academy.data.settings.SettingsRepository
import com.meow.academy.runtime.AppLifecycleObserver
import com.meow.academy.runtime.DshKeepAliveWorker
import com.meow.academy.runtime.RuntimeExtractor
import com.meow.academy.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 喵仓 Application：持有全局单例（设置仓库 / 运行时管理器等） */
class MeowAcademyApp : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val runtimeManager: RuntimeManager by lazy { RuntimeManager(this, settingsRepository) }

    /** 模型目录本地缓存（前后端解耦：UI 先用缓存渲染，DSH 就绪后再同步） */
    val modelCatalogRepository: ModelCatalogRepository by lazy { ModelCatalogRepository(this) }

    /** Markdown 渲染配置（appconfig/markdown-config.jsonc，JSONC 热更 + AI 可编排） */
    val markdownConfigRepository: MarkdownConfigRepository by lazy { MarkdownConfigRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // phase4 目录重构：进程启动即确保 workspace/appconfig/.agents 就绪（三保险之一；
        // 覆盖「先打开文件管理页、不启动 DSH」的场景）
        RuntimeExtractor.ensureAppDirs(this)
        // Markdown 渲染配置：config-defaults 同步 + seed appconfig/markdown-config.jsonc + FileObserver 热更
        markdownConfigRepository.start()
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