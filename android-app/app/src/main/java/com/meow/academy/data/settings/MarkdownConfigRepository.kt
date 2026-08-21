package com.meow.academy.data.settings

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.meow.academy.runtime.RuntimeExtractor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable

/**
 * Markdown 渲染配置仓库（appconfig/markdown-config.js）。
 *
 * - 首次启动从 assets 复制种子文件到 `filesDir/appconfig/markdown-config.js`（不覆盖已有）；
 * - 用 Rhino 解释模式求值 JS，得到原始配置 [MarkdownConfigRaw] 并发布到 [config]；
 * - FileObserver 监听文件变化（人工 / DSH AI write 都会触发），300ms 去抖后热重载；
 * - 文件缺失 / JS 语法错误时保留上次有效配置（首次用内置默认值），不崩溃。
 */
class MarkdownConfigRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _config = MutableStateFlow<MarkdownConfigRaw?>(null)
    private var fileObserver: FileObserver? = null
    private var reloadJob: Job? = null

    /** 当前原始配置；null = 文件缺失/未加载，渲染时用内置默认值 */
    val config: StateFlow<MarkdownConfigRaw?> = _config.asStateFlow()

    /** appconfig/markdown-config.js */
    val configFile: File
        get() = File(RuntimeExtractor.appConfigDir(context), FILE_NAME)

    /** 注册 FileObserver 并触发首次加载（幂等，可重复调用） */
    @Synchronized
    fun start() {
        if (fileObserver != null) return
        scope.launch { load() }
        // 观察 appconfig 目录而不是单文件：文件可能尚未生成，目录级观察能覆盖 CREATE/DELETE
        fileObserver = object : FileObserver(RuntimeExtractor.appConfigDir(context).absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FILE_NAME && event and WATCH_MASK != 0) requestReload()
            }
        }.also { it.startWatching() }
    }

    /** 立即重新加载（IO 线程）；失败时保留上次配置 */
    suspend fun load() {
        mutex.withLock {
            seedDefaultFile()
            val file = configFile
            val text = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
            if (text == null) {
                _config.value = null
                return
            }
            val raw = runCatching { evaluateJs(text) }
                .getOrElse {
                    Log.w(TAG, "markdown-config.js 求值失败，保留上次配置: ${it.message}")
                    return
                }
                ?.let { parseMarkdownConfigRaw(it) }
            _config.value = raw
            Log.d(TAG, "markdown-config.js 已加载，config=$raw")
        }
    }

    /** 300ms 去抖后重载（FileObserver 回调线程调用） */
    private fun requestReload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            load()
        }
    }

    /** 首次启动把 assets 里的种子文件复制到 appconfig；已有文件不覆盖（尊重用户/AI 修改） */
    private fun seedDefaultFile() {
        val target = configFile
        if (target.exists()) return
        runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "已生成种子配置: ${target.absolutePath}")
        }.onFailure {
            Log.w(TAG, "种子配置复制失败（继续用内置默认值）: ${it.message}")
        }
    }

    /** Rhino 解释模式求值 JS，读取全局 `markdownConfig` */
    private fun evaluateJs(code: String): Any? {
        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1 // Android 兼容：解释模式，不用编译字节码
            val scope: Scriptable = cx.initStandardObjects()
            cx.evaluateString(scope, code, FILE_NAME, 1, null)
            return scope.get("markdownConfig", scope)
        } finally {
            RhinoContext.exit()
        }
    }

    private companion object {
        const val TAG = "MarkdownConfigRepo"
        const val FILE_NAME = "markdown-config.js"
        const val ASSET_PATH = "appconfig/markdown-config.js"
        const val RELOAD_DEBOUNCE_MS = 300L
        const val WATCH_MASK = FileObserver.MODIFY or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_TO
    }
}
