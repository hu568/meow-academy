package com.meow.academy.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.meow.academy.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 运行时状态机（M2.2） */
sealed interface RuntimeState {
    /** 未安装：需要解压 assets/runtime.tar.gz */
    data object NotInstalled : RuntimeState

    /** 解压中，[progress] 0f..1f */
    data class Extracting(val progress: Float) : RuntimeState

    /** 已解压就绪，进程未启动 */
    data object Ready : RuntimeState

    /** DSH 进程运行中 */
    data object Running : RuntimeState

    /** 异常（解压失败 / 进程拉起失败 / key 缺失） */
    data class Error(val message: String) : RuntimeState
}

/**
 * DSH 运行时管理器：编排「解压 → 拉起前台服务 → DSH 进程」。
 *
 * 进程本身由 [DshRuntimeService] 持有；本类负责状态转换与对外暴露 RPC 客户端。
 */
class RuntimeManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.NotInstalled)
    val state: StateFlow<RuntimeState> = _state

    /** start() 互斥，防并发触发两次解压/启动 */
    private val startMutex = Mutex()

    /** 由 DshRuntimeService 设置；未启动时为空 */
    @Volatile
    var rpcClient: com.meow.academy.rpc.DshRpcClient? = null
        internal set

    /** DshRuntimeService 拉起进程成功后调用 */
    fun markRunning() {
        Log.i("RuntimeManager", "markRunning")
        _state.value = RuntimeState.Running
    }

    /** 进程退出 / 主动停止后调用：回收客户端并把状态回落（Error 状态保留给启动失败） */
    fun markStopped() {
        Log.i("RuntimeManager", "markStopped")
        if (_state.value is RuntimeState.Running) {
            _state.value = RuntimeState.Ready
        }
    }

    /** 启动失败：置 Error（保留给设置页展示，不再回落） */
    fun markLaunchFailed() {
        Log.i("RuntimeManager", "markLaunchFailed")
        _state.value = RuntimeState.Error("DSH 启动失败，请检查配置后重试")
    }

    /** 幂等启动：已运行直接返回 */
    suspend fun start() = startMutex.withLock {
        val s = _state.value
        when (s) {
            is RuntimeState.Running -> return@withLock
            is RuntimeState.Extracting -> return@withLock // 正在解压
            else -> Unit
        }
        Log.i("RuntimeManager", "start, state=${s::class.simpleName}")

        // 1) 确认运行时已解压（未装则解压）
        if (!RuntimeExtractor.isInstalled(context)) {
            _state.value = RuntimeState.NotInstalled
            val apiKey = settings.llmApiKey.first()
            if (apiKey.isBlank()) {
                _state.value = RuntimeState.Error("请先在「设置 → 模型管理」配置 DeepSeek API Key")
                return
            }
            try {
                RuntimeExtractor.extract(context) { progress ->
                    _state.value = RuntimeState.Extracting(progress)
                }
            } catch (e: Exception) {
                Log.e("RuntimeManager", "extract failed", e)
                _state.value = RuntimeState.Error("运行时解压失败：${e.message}")
                return
            }
        }
        _state.value = RuntimeState.Ready

        // 2) 启动前台服务（服务内拉起 DSH 进程并创建 RPC 客户端）
        val intent = Intent(context, DshRuntimeService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    /** 停止：通知服务停进程、回收客户端 */
    fun stop() {
        val intent = Intent(context, DshRuntimeService::class.java).setAction(DshRuntimeService.ACTION_STOP)
        context.startService(intent)
        rpcClient?.close()
        rpcClient = null
        if (_state.value is RuntimeState.Running) {
            _state.value = RuntimeState.Ready
        }
    }

    /** 运行时根目录 */
    fun runtimeDir(): File = RuntimeExtractor.runtimeDir(context)
}