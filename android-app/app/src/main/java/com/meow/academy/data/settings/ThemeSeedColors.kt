package com.meow.academy.data.settings

/**
 * 自定义主题种子色：默认值 + 预设色卡。
 *
 * 底层只持久化一个「种子色」（ARGB Long），浅色/深色整套 Material You 风格色板
 * 由 [com.meow.academy.ui.theme.CustomColorScheme] 自动派生，用户无需逐项调色。
 * 以后要支持更细粒度自定义（secondary/tertiary/背景色），在此扩展数据模型即可。
 */

/** 默认种子色：喵仓粉紫（与旧 CUSTOM 猫娘粉紫色板主色一致，老用户无缝过渡） */
const val DEFAULT_THEME_SEED_ARGB: Long = 0xFF8A5CF6L

/** 预设种子色条目：显示名 + ARGB */
data class ThemeSeedPreset(
    val name: String,
    val argb: Long,
)

/** 预设色卡（Material You 常用种子色，覆盖冷暖/深浅明度） */
val PRESET_THEME_SEEDS: List<ThemeSeedPreset> = listOf(
    ThemeSeedPreset("喵仓粉紫", 0xFF8A5CF6L),
    ThemeSeedPreset("深海蓝", 0xFF4D6BFEL),
    ThemeSeedPreset("晴空蓝", 0xFF1E88E5L),
    ThemeSeedPreset("薄荷青", 0xFF00A6A6L),
    ThemeSeedPreset("翡翠绿", 0xFF2E9E5BL),
    ThemeSeedPreset("柠檬黄", 0xFFF9A825L),
    ThemeSeedPreset("活力橙", 0xFFFF6D00L),
    ThemeSeedPreset("珊瑚粉", 0xFFEC407AL),
    ThemeSeedPreset("樱草红", 0xFFE53935L),
    ThemeSeedPreset("紫罗兰", 0xFF7E57C2L),
    ThemeSeedPreset("石墨蓝", 0xFF546E7AL),
    ThemeSeedPreset("暖棕", 0xFF8D6E63L),
)

/** ARGB Long → 可读 HEX（#RRGGBB），用于设置页展示/输入框 */
fun themeSeedToHex(argb: Long): String {
    val rgb = argb and 0xFFFFFFL
    return "#%06X".format(rgb)
}

/**
 * 解析 HEX 颜色字符串 → ARGB Long。
 * 支持 `#RRGGBB` / `RRGGBB` / `#AARRGGBB`；非法输入返回 null。
 */
fun themeSeedFromHex(hex: String): Long? {
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val value = runCatching { cleaned.toLong(16) }.getOrNull() ?: return null
    return when (cleaned.length) {
        6 -> 0xFF000000L or value
        8 -> value
        else -> null
    }
}
