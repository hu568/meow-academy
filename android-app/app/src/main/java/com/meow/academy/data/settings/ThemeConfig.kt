package com.meow.academy.data.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * 主题颜色动态配置（appconfig/theme-config.jsonc 解析结果，docs/design-dynamic-config.md §11）。
 *
 * 两层结构：
 * - [ThemeConfigRaw]：JSONC 解析 + 深合并后的原始 Map（未解析 {light, dark} 主题对象）；
 * - [ThemeConfig]：经 [resolveThemeConfig] 按当前主题解析后的具体值。
 *
 * 三重内容：
 * - `seed`：种子色（`#RRGGBB` / `#AARRGGBB`），覆盖后整套浅/深色板由它自动派生（Material You 风格）；
 * - `overrides`：具体色槽直接覆盖（primary / secondary / background / …），
 *   未覆盖的色槽继续用种子派生值，`null` = 不覆盖；
 * - `backgrounds`：聊天背景动态配置（设置页勾选「使用动态配置」后生效）——
 *   `active` 当前选中的背景，`presets` 预设渐变定义（数组整体替换，留空 = 用 Kotlin 内置）；
 * - `components`：组件级色槽（工具折叠条 / 文件快捷栏等），
 *   为特定 UI 提供独立颜色，不影响整体色板，null = 跟随主题色板对应色槽。
 *
 * 只持久化「覆盖了什么」，没有覆盖的永远回退默认 → 深浅两套配色各自生效。
 */
typealias ThemeConfigRaw = Map<String, Any?>

/** 主题颜色动态配置（解析结果） */
data class ThemeConfig(
    /** 种子色 HEX；null = 使用内置默认种子（喵仓粉紫）自动派生 */
    val seed: String? = null,
    /** 具体色槽覆盖：色槽名 → 颜色 HEX（key 必须是 [KNOWN_THEME_SLOTS] 白名单） */
    val overrides: Map<String, String> = emptyMap(),
    /** 聊天背景动态配置（预设定义 + 当前选中） */
    val backgrounds: BackgroundsConfig = BackgroundsConfig(),
    /** 组件级色槽（工具折叠条 / 文件快捷栏等，null = 跟随主题对应色槽） */
    val components: ComponentsConfig = ComponentsConfig(),
)

/** 组件级颜色配置（为特定 UI 提供独立色槽，key 必须是 [KNOWN_COMPONENT_SLOTS] 白名单） */
data class ComponentsConfig(
    /** 工具调用折叠条背景色，null = 跟随 secondaryContainer */
    val toolGroupBackground: String? = null,
    /** 工具调用折叠条图标/文字色，null = 跟随 onSecondaryContainer */
    val toolGroupContent: String? = null,
    /** 工具调用成功（✓）状态色，null = 跟随 primary */
    val toolStatusColor: String? = null,
    /** 文件面包屑导航文字/图标色，null = 跟随 primary */
    val quickBarColor: String? = null,
    /** 文件快捷栏（FilterChip）选中态容器色，null = 跟随 primaryContainer */
    val quickBarSelectedContainer: String? = null,
)

/** 聊天背景动态配置 */
data class BackgroundsConfig(
    /** 当前选中的背景："none" / "preset:<id>" / "file:<相对 appconfig/ 的路径>" */
    val active: String = "none",
    /** 预设渐变定义；空 = 使用 Kotlin 内置 [CHAT_BG_PRESETS] */
    val presets: List<ChatBgPresetDef> = emptyList(),
)

/** 渐变预设定义（与 Kotlin 内置 [ChatBgPreset] 同构，colors 为 ARGB Long） */
data class ChatBgPresetDef(
    val id: String,
    val name: String,
    val colors: List<Long>,
)

/**
 * 把原始配置按当前主题解析为具体配置；null / 缺字段 / 非法颜色时逐项忽略（回退种子派生）。
 */
fun resolveThemeConfig(raw: ThemeConfigRaw?, isDark: Boolean): ThemeConfig {
    val root = raw ?: return ThemeConfig()
    val seed = (root["seed"] as? String)?.takeIf { it.matches(HEX_COLOR_REGEX) }
    val backgrounds = parseBackgrounds(root["backgrounds"])
    val overridesRaw = root["overrides"] as? Map<*, *>
    val overrides = LinkedHashMap<String, String>()
    if (overridesRaw != null) {
        for ((key, value) in overridesRaw) {
            val slot = key.toString()
            if (slot !in KNOWN_THEME_SLOTS) continue // 未知色槽忽略，防手滑
            val resolved = value.resolveThemeSlot(isDark) ?: continue
            val color = resolved as? String ?: continue
            if (!color.matches(HEX_COLOR_REGEX)) continue
            overrides[slot] = color
        }
    }
    return ThemeConfig(seed, overrides, backgrounds, parseComponents(root["components"], isDark))
}

/** 可被动态覆盖的 ColorScheme 色槽白名单（未知 key 忽略） */
val KNOWN_THEME_SLOTS: Set<String> = setOf(
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground",
    "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "surfaceTint",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant",
)

/** 可被动态覆盖的组件级色槽白名单（未知 key 忽略） */
val KNOWN_COMPONENT_SLOTS: Set<String> = setOf(
    "toolGroupBackground",
    "toolGroupContent",
    "toolStatusColor",
    "quickBarColor",
    "quickBarSelectedContainer",
)

// ── 组件级颜色解析 ──────────────────────────────────────────────────────────

private fun parseComponents(raw: Any?, isDark: Boolean): ComponentsConfig {
    val map = toMap(raw) ?: return ComponentsConfig()
    fun slot(key: String): String? {
        val resolved = map[key].resolveThemeSlot(isDark) ?: return null
        val color = resolved as? String ?: return null
        return color.takeIf { it.matches(HEX_COLOR_REGEX) }
    }
    return ComponentsConfig(
        toolGroupBackground = slot("toolGroupBackground"),
        toolGroupContent = slot("toolGroupContent"),
        toolStatusColor = slot("toolStatusColor"),
        quickBarColor = slot("quickBarColor"),
        quickBarSelectedContainer = slot("quickBarSelectedContainer"),
    )
}

// ── 聊天背景解析 ────────────────────────────────────────────────────────────

private fun parseBackgrounds(raw: Any?): BackgroundsConfig {
    val map = toMap(raw) ?: return BackgroundsConfig()
    val active = map["active"] as? String ?: "none"
    val presets = parsePresets(map["presets"])
    return BackgroundsConfig(active, presets)
}

private fun parsePresets(raw: Any?): List<ChatBgPresetDef> {
    val list = toList(raw) ?: return emptyList()
    return list.mapNotNull { item ->
        val map = toMap(item) ?: return@mapNotNull null
        val id = map["id"] as? String ?: return@mapNotNull null
        val name = map["name"] as? String ?: return@mapNotNull null
        val colors = (map["colors"] as? List<*>)
            ?.mapNotNull { (it as? String)?.let(::themeSeedFromHex) }
            ?: return@mapNotNull null
        if (colors.size < 2) return@mapNotNull null
        ChatBgPresetDef(id, name, colors)
    }
}

// ── 递归转换：JSONC 原始 Map → Kotlin Map / List ──────────────────────────

private fun toMap(value: Any?): Map<String, Any?>? = when (value) {
    is Map<*, *> -> {
        val map = LinkedHashMap<String, Any?>()
        for ((k, v) in value) {
            map[k.toString()] = toPlain(v)
        }
        map
    }
    else -> null
}

private fun toList(value: Any?): List<Any?>? = when (value) {
    is List<*> -> value.map { toPlain(it) }
    else -> null
}

/** 把 JSONC 解析出的值转成纯 Kotlin 值（Map / List / 基本类型 / null） */
private fun toPlain(value: Any?): Any? = when (value) {
    is Map<*, *> -> toMap(value)
    is List<*> -> toList(value)
    else -> value
}

// ── 主题感知 ──────────────────────────────────────────────────────────────

/** 若值是 `{ light: X, dark: Y }` 则按当前主题选取，否则原样返回 */
private fun Any?.resolveThemeSlot(isDark: Boolean): Any? {
    val map = this as? Map<*, *> ?: return this
    if (!map.containsKey("light") && !map.containsKey("dark")) return this
    val key = if (isDark) "dark" else "light"
    if (map.containsKey(key)) return map[key]
    return map[if (isDark) "light" else "dark"]
}

private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")

// ── JSONC 带注释输出（设置页写回 backgrounds.active 用，docs/design-dynamic-config.md §3/§5） ──

private val THEME_SLOT_COMMENTS: Map<String, String> = mapOf(
    "primary" to "主色（按钮 / 高亮 / 选中态）",
    "onPrimary" to "主色上的文字 / 图标色",
    "primaryContainer" to "主色容器背景",
    "onPrimaryContainer" to "主色容器上的文字色",
    "secondary" to "次要色（次级按钮 / 徽标）",
    "onSecondary" to "次要色上的文字色",
    "secondaryContainer" to "次要容器背景",
    "onSecondaryContainer" to "次要容器文字色",
    "tertiary" to "第三色（点缀 / 强调）",
    "onTertiary" to "第三色上的文字色",
    "tertiaryContainer" to "第三容器背景",
    "onTertiaryContainer" to "第三容器文字色",
    "background" to "页面背景",
    "onBackground" to "页面背景上的文字色",
    "surface" to "表面色（卡片 / 弹层）",
    "onSurface" to "表面上的文字色",
    "surfaceVariant" to "表面变体（侧栏 / 输入框底）",
    "onSurfaceVariant" to "表面变体上的次要文字",
    "surfaceTint" to "表面色调（通常 = 主色）",
    "error" to "错误提示色",
    "onError" to "错误色上的文字色",
    "errorContainer" to "错误容器背景",
    "onErrorContainer" to "错误容器文字色",
    "outline" to "分隔线 / 描边（强）",
    "outlineVariant" to "分隔线 / 描边（弱）",
)

/**
 * 把 theme-config 配置 Map 格式化为带注释的 JSONC 文本。
 *
 * 每个字段后面都有注释，每段前面有 Section 标题注释，顶部有统一文件头。
 * 设置页「动态配置」模式写回 `backgrounds.active` 时使用（保留用户其它修改）。
 */
fun formatThemeConfigJsonc(
    values: Map<String, Any?>,
    version: String,
    editableCount: Int,
): String {
    val sb = StringBuilder()
    sb.append("// 🐾 喵仓 主题颜色 配置\n")
    sb.append("// 使用说明见 appconfig/README.md\n")
    sb.append("// 可修改项：$editableCount · 版本：$version\n")
    sb.append("{\n")
    sb.append("  \"version\": ").append(JSONObject.quote(version)).append(",\n")
    sb.append("  \"_editableCount\": ").append(editableCount).append(",\n")

    // seed
    sb.append('\n')
    sb.append("  // ── 种子色（整套色板自动派生的根） ─────\n")
    sb.append("  \"seed\": ").append(jsonValueToString(values["seed"]))
    sb.append("  // 种子色 #RRGGBB；null = 内置默认（喵仓粉紫 #8A5CF6），浅/深两套色板自动派生（主题感知）\n")

    // overrides
    sb.append('\n')
    sb.append("  // ── 具体色槽覆盖 ──\n")
    sb.append("  \"overrides\": {\n")
    val overrides = values["overrides"] as? Map<*, *> ?: emptyMap<String, Any?>()
    val slots = KNOWN_THEME_SLOTS.toList()
    for (i in slots.indices) {
        val slot = slots[i]
        val value = overrides[slot]
        val comma = if (i < slots.size - 1) "," else ""
        sb.append("    \"").append(slot).append("\": ").append(jsonValueToString(value)).append(comma)
        sb.append("  // ").append(THEME_SLOT_COMMENTS[slot] ?: "").append('\n')
    }
    sb.append("  },\n")

    // backgrounds
    sb.append('\n')
    sb.append("  // ── 聊天背景（backgrounds） ──\n")
    sb.append("  // 设置页「使用动态配置」勾选后，背景预设与当前选择都从这里读；\n")
    sb.append("  // 自定义背景图片是二进制资源，必须放在 appconfig/images/ 子文件夹（见 appconfig/README.md）。\n")
    val backgrounds = values["backgrounds"] as? Map<*, *> ?: emptyMap<String, Any?>()
    sb.append("  \"backgrounds\": {\n")
    sb.append("    \"active\": ").append(jsonValueToString(backgrounds["active"]))
    sb.append("  // 当前选中的聊天背景：\"none\" / \"preset:<id>\" / \"file:images/<文件名>\"（相对 appconfig/ 的路径）\n")
    sb.append("    \"presets\": ").append(jsonValueToString(backgrounds["presets"]))
    sb.append("  // 预设渐变定义（数组整体替换）；留空 [] = 使用内置 6 个预设\n")
    sb.append("  },\n")

    // components
    sb.append('\n')
    sb.append("  // ── 组件级颜色（components） ──\n")
    sb.append("  // 为特定 UI 组件提供独立色槽，不影响整体主题色板；null = 跟随主题色板对应色槽。\n")
    val components = values["components"] as? Map<*, *> ?: emptyMap<String, Any?>()
    val componentSlots = KNOWN_COMPONENT_SLOTS.toList()
    sb.append("  \"components\": {\n")
    for (i in componentSlots.indices) {
        val slot = componentSlots[i]
        val value = components[slot]
        val comma = if (i < componentSlots.size - 1) "," else ""
        sb.append("    \"").append(slot).append("\": ").append(jsonValueToString(value)).append(comma)
        sb.append("  // ").append(COMPONENT_SLOT_COMMENTS[slot] ?: "").append('\n')
    }
    sb.append("  }\n")

    sb.append("}\n")
    return sb.toString()
}

private val COMPONENT_SLOT_COMMENTS: Map<String, String> = mapOf(
    "toolGroupBackground" to "工具调用折叠条背景色，null = secondaryContainer",
    "toolGroupContent" to "工具调用折叠条图标/文字色，null = onSecondaryContainer",
    "toolStatusColor" to "工具调用成功（✓）状态色，null = primary",
    "quickBarColor" to "文件面包屑导航文字/图标色，null = primary",
    "quickBarSelectedContainer" to "文件快捷栏（FilterChip）选中态容器色，null = primaryContainer",
)

private fun jsonValueToString(value: Any?): String = when (value) {
    null -> "null"
    is String -> JSONObject.quote(value)
    is Boolean, is Number -> value.toString()
    is Map<*, *> -> JSONObject(value).toString()
    is List<*> -> JSONArray(value).toString()
    else -> JSONObject.quote(value.toString())
}
