package com.meow.academy.data.settings

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 聊天底图设置：无背景 / 内置渐变预设 / 相册导入图片。
 *
 * 持久化为字符串（DataStore）：
 * - `none`              无背景
 * - `preset:<id>`       内置渐变预设（见 [CHAT_BG_PRESETS]）
 * - `file:<绝对路径>`   相册图片已拷贝到 App 私有目录后的路径
 *
 * 预设只存 ARGB Long（不依赖 Compose 类型），与 ThemeSeedColors 同一风格，
 * UI 层负责转换成 Brush/Color。
 */

/** 无背景的持久化值 */
const val CHAT_BG_NONE = "none"

private const val CHAT_BG_PRESET_PREFIX = "preset:"
private const val CHAT_BG_FILE_PREFIX = "file:"

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

/** 聊天底图的 UI 模型：由持久化字符串解析得到 */
sealed interface ChatBackground {
    /** 无背景：使用主题默认背景色 */
    data object None : ChatBackground

    /** 内置渐变预设 */
    data class Preset(val preset: ChatBgPreset) : ChatBackground

    /** 相册导入的自定义图片（已拷贝到 App 私有目录） */
    data class File(val path: String) : ChatBackground
}

/** 解析持久化字符串 → 聊天底图；未知/损坏值安全回退 [ChatBackground.None] */
fun parseChatBackground(raw: String?): ChatBackground {
    if (raw.isNullOrBlank() || raw == CHAT_BG_NONE) return ChatBackground.None
    if (raw.startsWith(CHAT_BG_PRESET_PREFIX)) {
        val id = raw.removePrefix(CHAT_BG_PRESET_PREFIX)
        return CHAT_BG_PRESETS.firstOrNull { it.id == id }
            ?.let { ChatBackground.Preset(it) }
            ?: ChatBackground.None
    }
    if (raw.startsWith(CHAT_BG_FILE_PREFIX)) {
        val path = raw.removePrefix(CHAT_BG_FILE_PREFIX)
        if (path.isNotBlank()) return ChatBackground.File(path)
    }
    return ChatBackground.None
}

/** 设置页副标题：当前底图的显示名 */
fun chatBackgroundLabel(raw: String?): String = when (val bg = parseChatBackground(raw)) {
    ChatBackground.None -> "无背景"
    is ChatBackground.Preset -> bg.preset.name
    is ChatBackground.File -> "自定义图片"
}

/** 把相册图片拷贝进 App 私有目录（避免 content URI 授权失效），返回 `file:` 持久化字符串；失败返回 null */
fun copyImageToAppStorage(context: Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "chat-bg").apply { mkdirs() }
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
    CHAT_BG_FILE_PREFIX + out.absolutePath
}.getOrNull()

/** 从持久化字符串中提取自定义图片路径；非 `file:` 背景返回 null */
fun chatBackgroundFilePath(raw: String?): String? =
    (parseChatBackground(raw) as? ChatBackground.File)?.path
