package com.meow.academy.data.settings

import android.content.Context
import android.net.Uri
import com.meow.academy.runtime.RuntimeExtractor
import java.io.File
import kotlinx.coroutines.flow.firstOrNull

/**
 * 聊天底图设置：无背景 / 渐变预设 / 自定义图片。
 *
 * 持久化格式（[ChatBackground.File] 的路径统一为**相对 appconfig/ 的路径**，如 `images/bg.jpg`）：
 * - `none`              无背景
 * - `preset:<id>`       渐变预设（Kotlin 内置 [CHAT_BG_PRESETS] 或 JSONC backgrounds.presets）
 * - `file:<相对路径>`   自定义图片（存于 `appconfig/images/`，二进制资源必须放 appconfig 子文件夹）
 *
 * 两种管理模式（设置页「使用动态配置」复选框）：
 * - **简单模式（不勾选）**：预设用 Kotlin 内置，自定义图片固定文件名 [CHAT_BG_FIXED_FILE_NAME]，
 *   设置页「替换图片」= 直接覆盖该文件；当前选择存 DataStore。
 * - **动态配置模式（勾选）**：预设与当前选择都从 theme-config.jsonc 的 `backgrounds` 读，
 *   AI 可直接改配置 / 放置图片文件；选择写回 JSONC `backgrounds.active`。
 *
 * 旧版 `filesDir/chat-bg/` 目录已迁移到 `appconfig/images/`（见 [migrateLegacyChatBg]）。
 */

/** 无背景的持久化值 */
const val CHAT_BG_NONE = "none"

/** 简单模式固定自定义图片文件名（appconfig/images/bg.jpg） */
const val CHAT_BG_FIXED_FILE_NAME = "bg.jpg"

private const val CHAT_BG_PRESET_PREFIX = "preset:"
private const val CHAT_BG_FILE_PREFIX = "file:"
private const val IMAGES_SUBDIR = "images"

/** 旧版自定义图片目录名（filesDir/chat-bg/，已弃用） */
private const val LEGACY_CHAT_BG_DIR = "chat-bg"

/** 自定义背景图片目录（appconfig/images/，二进制资源必须放 appconfig 子文件夹） */
fun chatBgImagesDir(context: Context): File =
    File(RuntimeExtractor.appConfigDir(context), IMAGES_SUBDIR)

/** 内置渐变预设条目：id 用于持久化，argbColors 为渐变节点（至少 2 个） */
data class ChatBgPreset(
    val id: String,
    val name: String,
    val argbColors: List<Long>,
)

/** 内置聊天底图预设（暖/冷/暗/亮都有，浅深主题下配合遮罩均可读） */
val CHAT_BG_PRESETS: List<ChatBgPreset> = listOf(
    ChatBgPreset("dusk", "暮色紫", listOf(0xFF2E1A47, 0xFF7A5AF8, 0xFFB388FF)),
    ChatBgPreset("ocean", "深海蓝", listOf(0xFF0F2027, 0xFF203A43, 0xFF2C5364)),
    ChatBgPreset("mint", "薄荷绿", listOf(0xFF0F9B8E, 0xFF1D976C, 0xFF93F9B9)),
    ChatBgPreset("sunset", "暖橙日落", listOf(0xFFFF7E5F, 0xFFFEB47B, 0xFFFFD194)),
    ChatBgPreset("sakura", "樱粉", listOf(0xFFF78CA0, 0xFFF9748F, 0xFFF5576C)),
    ChatBgPreset("graphite", "石墨", listOf(0xFF232526, 0xFF414345, 0xFF616161)),
)

/** 聊天底图的 UI 模型：由持久化字符串 + 当前预设列表解析得到 */
sealed interface ChatBackground {
    /** 无背景：使用主题默认背景色 */
    data object None : ChatBackground

    /** 渐变预设（已解析为绝对色值） */
    data class Preset(val preset: ChatBgPreset) : ChatBackground

    /** 自定义图片（path 为解析后的绝对路径） */
    data class File(val path: String) : ChatBackground
}

/**
 * 解析持久化字符串 → 聊天底图；未知/损坏值安全回退 [ChatBackground.None]。
 *
 * @param presets     预设来源（Kotlin 内置或 JSONC 配置）
 * @param appConfigDir appconfig 目录；`file:` 后的相对路径（如 `images/bg.jpg`）据此解析为绝对路径，
 *                     旧版绝对路径直接使用；null 时相对路径按原样（向后兼容旧调用方）
 */
fun parseChatBackground(
    raw: String?,
    presets: List<ChatBgPreset> = CHAT_BG_PRESETS,
    appConfigDir: File? = null,
): ChatBackground {
    if (raw.isNullOrBlank() || raw == CHAT_BG_NONE) return ChatBackground.None
    if (raw.startsWith(CHAT_BG_PRESET_PREFIX)) {
        val id = raw.removePrefix(CHAT_BG_PRESET_PREFIX)
        return presets.firstOrNull { it.id == id }
            ?.let { ChatBackground.Preset(it) }
            ?: ChatBackground.None
    }
    if (raw.startsWith(CHAT_BG_FILE_PREFIX)) {
        val path = raw.removePrefix(CHAT_BG_FILE_PREFIX)
        if (path.isNotBlank()) {
            val file = if (appConfigDir != null && !File(path).isAbsolute) {
                File(appConfigDir, path)
            } else {
                File(path)
            }
            return ChatBackground.File(file.absolutePath)
        }
    }
    return ChatBackground.None
}

/**
 * 统一解析「当前应显示的聊天背景」：合并管理模式 + DataStore + JSONC 配置。
 *
 * @param dynamicEnabled 设置页「使用动态配置」勾选状态
 * @param dataStoreRaw   DataStore 持久化值（简单模式用）
 * @param configRaw      theme-config.jsonc 深合并后的原始 Map（动态模式用）
 * @param appConfigDir   appconfig 目录（解析相对路径）
 */
fun resolveChatBackground(
    dynamicEnabled: Boolean,
    dataStoreRaw: String,
    configRaw: ThemeConfigRaw?,
    appConfigDir: File,
): ChatBackground {
    val (raw, presets) = if (dynamicEnabled) {
        val cfg = resolveThemeConfig(configRaw, isDark = false)
        val defs = cfg.backgrounds.presets
        val presetList = if (defs.isNotEmpty()) {
            defs.map { ChatBgPreset(it.id, it.name, it.colors) }
        } else {
            CHAT_BG_PRESETS
        }
        cfg.backgrounds.active to presetList
    } else {
        dataStoreRaw to CHAT_BG_PRESETS
    }
    return parseChatBackground(raw, presets, appConfigDir)
}

/** 聊天背景的「设置页/对话框」视图：当前持久化值 + 当前可用的预设列表 */
data class ChatBackgroundUi(
    val raw: String,
    val presets: List<ChatBgPreset>,
)

/**
 * 统一解析「当前背景 + 可用预设」（设置页 / 聊天页共用）：
 * - 动态配置模式：raw = JSONC backgrounds.active，presets = JSONC 预设（空回退 Kotlin 内置）；
 * - 简单模式：raw = DataStore 值，presets = Kotlin 内置。
 */
fun resolveChatBackgroundUi(
    dynamicEnabled: Boolean,
    dataStoreRaw: String,
    configRaw: ThemeConfigRaw?,
): ChatBackgroundUi {
    if (!dynamicEnabled) return ChatBackgroundUi(dataStoreRaw, CHAT_BG_PRESETS)
    val cfg = resolveThemeConfig(configRaw, isDark = false)
    val defs = cfg.backgrounds.presets
    val presets = if (defs.isNotEmpty()) {
        defs.map { ChatBgPreset(it.id, it.name, it.colors) }
    } else {
        CHAT_BG_PRESETS
    }
    return ChatBackgroundUi(cfg.backgrounds.active, presets)
}

/** 设置页副标题：当前底图的显示名 */
fun chatBackgroundLabel(raw: String?): String = when (val bg = parseChatBackground(raw)) {
    ChatBackground.None -> "无背景"
    is ChatBackground.Preset -> bg.preset.name
    is ChatBackground.File -> "自定义图片"
}

/** 简单模式固定自定义图片的持久化字符串（file:images/bg.jpg） */
fun fixedChatBgRaw(): String = CHAT_BG_FILE_PREFIX + "$IMAGES_SUBDIR/$CHAT_BG_FIXED_FILE_NAME"

/**
 * 简单模式：把相册图片拷贝到 `appconfig/images/bg.jpg`（固定文件名，直接覆盖替换）。
 * 返回 `file:` 持久化字符串；失败返回 null。
 */
fun copyImageToAppStorage(context: Context, uri: Uri): String? = runCatching {
    val dir = chatBgImagesDir(context).apply { mkdirs() }
    val out = File(dir, CHAT_BG_FIXED_FILE_NAME)
    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
        true
    } ?: false
    if (!copied) return@runCatching null
    CHAT_BG_FILE_PREFIX + "$IMAGES_SUBDIR/$CHAT_BG_FIXED_FILE_NAME"
}.getOrNull()

/**
 * 动态配置模式：把相册图片拷贝到 `appconfig/images/`（时间戳文件名，不覆盖已有文件）。
 * 返回 `file:` 持久化字符串（相对 appconfig/）；失败返回 null。
 */
fun copyImageDynamicToAppStorage(context: Context, uri: Uri): String? = runCatching {
    val dir = chatBgImagesDir(context).apply { mkdirs() }
    val ext = when (context.contentResolver.getType(uri)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        else -> ".jpg"
    }
    val out = File(dir, "bg_${System.currentTimeMillis()}$ext")
    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
        true
    } ?: false
    if (!copied) return@runCatching null
    CHAT_BG_FILE_PREFIX + "$IMAGES_SUBDIR/" + out.name
}.getOrNull()

/** 从持久化字符串中提取自定义图片路径（相对 appConfigDir 或绝对路径）；非 `file:` 背景返回 null */
fun chatBackgroundFilePath(raw: String?): String? =
    (parseChatBackground(raw) as? ChatBackground.File)?.path

/** 把持久化 `file:` 路径解析为绝对 File；兼容相对 appConfigDir 路径与旧绝对路径 */
fun resolveChatBgFile(context: Context, path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else File(RuntimeExtractor.appConfigDir(context), path)
}

/**
 * 一次性迁移：把旧 `filesDir/chat-bg/` 的文件搬到 `appconfig/images/`，并修复 DataStore 里
 * 指向旧目录的 `file:<绝对路径>` → `file:images/<文件名>`。幂等（目录不存在直接返回）。
 */
suspend fun migrateLegacyChatBg(context: Context, repository: SettingsRepository) {
    val legacyDir = File(context.filesDir, LEGACY_CHAT_BG_DIR)
    if (!legacyDir.isDirectory) return
    val imagesDir = chatBgImagesDir(context).apply { mkdirs() }
    runCatching {
        legacyDir.listFiles()?.forEach { f ->
            if (f.isFile) {
                val target = File(imagesDir, f.name)
                if (!target.exists()) f.copyTo(target)
                f.delete()
            }
        }
        legacyDir.delete()
    }.onFailure {
        // 迁移失败不阻塞启动；DataStore 路径修复仍尝试
    }
    val oldRaw = repository.chatBackground.firstOrNull()
    val oldPath = chatBackgroundFilePath(oldRaw) ?: return
    val oldFile = File(oldPath)
    if (oldFile.parentFile?.name == LEGACY_CHAT_BG_DIR) {
        repository.setChatBackground(CHAT_BG_FILE_PREFIX + "$IMAGES_SUBDIR/" + oldFile.name)
    }
}
