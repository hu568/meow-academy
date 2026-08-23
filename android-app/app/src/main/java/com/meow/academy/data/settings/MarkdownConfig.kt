package com.meow.academy.data.settings

import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject

/**
 * Markdown 渲染配置（appconfig/markdown-config.js 解析结果）。
 *
 * 两层结构：
 * - [MarkdownConfigRaw]：JS 求值后的原始 Map（未解析 {light, dark} 主题对象）；
 * - [MarkdownConfig]：经 [resolveMarkdownConfig] 按当前主题解析后的具体值。
 *
 * 这样做是为了让 `{ light: X, dark: Y }` 在深浅色切换时各自生效，
 * 仓库只负责存原始配置，不关心主题。
 */
typealias MarkdownConfigRaw = Map<String, Any?>

/** JS 配置的默认值（文件缺失 / 字段缺失时的回退） */
data class MarkdownConfig(
    val formula: FormulaConfig = FormulaConfig(),
    val list: ListConfig = ListConfig(),
    val code: CodeConfig = CodeConfig(),
    val quote: QuoteConfig = QuoteConfig(),
    val link: LinkConfig = LinkConfig(),
    val heading: HeadingConfig = HeadingConfig(),
    val thematicBreak: ThematicBreakConfig = ThematicBreakConfig(),
    val table: TableConfig = TableConfig(),
    val mermaid: MermaidConfig = MermaidConfig(),
    val image: ImageConfig = ImageConfig(),
) {
    data class FormulaConfig(
        val blockCornerRadiusDp: Float = 12f,
        val blockBackground: String? = null,
        val blockPaddingDp: Padding = Padding(16f, 8f, 16f, 8f),
        val blockFitCanvas: Boolean = true,
        val blockAlign: Int = 1,
        val blockTextColor: String? = null,
        val inlineTextColor: String? = null,
    )

    data class ListConfig(
        val bulletWidthDp: Float = 6f,
        val bulletStrokeWidthDp: Float = 1f,
        val itemColor: String? = null,
    )

    data class CodeConfig(
        val blockCornerRadiusDp: Float = 10f,
        val blockBackground: String? = null,
        val blockMarginDp: Float = 8f,
        val blockTextSizeRatio: Float = 0.85f,
        val textSizeRatio: Float = 0.85f,
        val blockTextColor: String? = null,
        val textColor: String? = null,
        val inlineCornerRadiusDp: Float = 6f,
        val inlineBackground: String? = null,
        val inlinePaddingDp: Padding = Padding(5f, 2f, 5f, 2f),
    )

    data class QuoteConfig(
        val color: String? = null,
        val widthDp: Float = 4f,
    )

    data class LinkConfig(
        val color: String? = null,
        val underlined: Boolean = true,
    )

    data class HeadingConfig(
        val sizeMultipliers: List<Float> = listOf(1.6f, 1.4f, 1.25f, 1.15f, 1.1f, 1.0f),
    )

    data class ThematicBreakConfig(
        val color: String? = null,
        val heightDp: Float = 2f,
    )

    /** M5 表格配置（Compose 表格组件读取） */
    data class TableConfig(
        val cornerRadiusDp: Float = 12f,
        val headerBackground: String? = null,
        val rowAltBackground: String? = null,
        val borderColor: String? = null,
        val borderWidthDp: Float = 1f,
        val cellPaddingDp: Padding = Padding(10f, 6f, 10f, 6f),
        val copyButtonColor: String? = null,
    )

    /** M5 mermaid 配置（WebView 渲染主题 + 图块外观） */
    data class MermaidConfig(
        val theme: String = "",
        val cornerRadiusDp: Float = 12f,
        val blockBackground: String? = null,
    )

    /** M5.5 图片块配置（圆角线框外观，聊天气泡 / 知识库共用） */
    data class ImageConfig(
        val cornerRadiusDp: Float = 12f,
        val borderWidthDp: Float = 1f,
        val borderColor: String? = null,
        val maxHeightDp: Float = 320f,
        val loadingBackground: String? = null,
        val errorText: String = "图片加载失败",
    )

    data class Padding(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )
}

/**
 * 把 Rhino 求值结果（全局 `markdownConfig` 变量）转成原始配置 Map。
 *
 * @param raw Rhino NativeObject / NativeArray / 基本类型 / null
 * @return 原始配置 Map；无法转换时返回 null（调用方走默认值）
 */
fun parseMarkdownConfigRaw(raw: Any?): MarkdownConfigRaw? = toMap(raw)

/** 把原始配置按当前主题解析为具体配置；null / 缺字段时逐项回退默认值 */
fun resolveMarkdownConfig(raw: MarkdownConfigRaw?, isDark: Boolean): MarkdownConfig {
    val root = raw ?: return MarkdownConfig()

    fun section(name: String): Map<String, Any?>? =
        toMap(root[name]?.resolveTheme(isDark))

    return MarkdownConfig(
        formula = MarkdownConfig.FormulaConfig(
            blockCornerRadiusDp = section("formula")?.get("blockCornerRadiusDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.FormulaConfig().blockCornerRadiusDp),
            blockBackground = section("formula")?.get("blockBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.FormulaConfig().blockBackground),
            blockPaddingDp = section("formula")?.get("blockPaddingDp")
                ?.resolveTheme(isDark).asPadding(MarkdownConfig.FormulaConfig().blockPaddingDp),
            blockFitCanvas = section("formula")?.get("blockFitCanvas")
                ?.resolveTheme(isDark).asBoolean(MarkdownConfig.FormulaConfig().blockFitCanvas),
            blockAlign = section("formula")?.get("blockAlign")
                ?.resolveTheme(isDark).asInt(MarkdownConfig.FormulaConfig().blockAlign),
            blockTextColor = section("formula")?.get("blockTextColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.FormulaConfig().blockTextColor),
            inlineTextColor = section("formula")?.get("inlineTextColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.FormulaConfig().inlineTextColor),
        ),
        list = MarkdownConfig.ListConfig(
            bulletWidthDp = section("list")?.get("bulletWidthDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.ListConfig().bulletWidthDp),
            bulletStrokeWidthDp = section("list")?.get("bulletStrokeWidthDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.ListConfig().bulletStrokeWidthDp),
            itemColor = section("list")?.get("itemColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.ListConfig().itemColor),
        ),
        code = MarkdownConfig.CodeConfig(
            blockCornerRadiusDp = section("code")?.get("blockCornerRadiusDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.CodeConfig().blockCornerRadiusDp),
            blockBackground = section("code")?.get("blockBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.CodeConfig().blockBackground),
            blockMarginDp = section("code")?.get("blockMarginDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.CodeConfig().blockMarginDp),
            blockTextSizeRatio = section("code")?.get("blockTextSizeRatio")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.CodeConfig().blockTextSizeRatio),
            textSizeRatio = section("code")?.get("textSizeRatio")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.CodeConfig().textSizeRatio),
            blockTextColor = section("code")?.get("blockTextColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.CodeConfig().blockTextColor),
            textColor = section("code")?.get("textColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.CodeConfig().textColor),
            inlineCornerRadiusDp = section("code")?.get("inlineCornerRadiusDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.CodeConfig().inlineCornerRadiusDp),
            inlineBackground = section("code")?.get("inlineBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.CodeConfig().inlineBackground),
            inlinePaddingDp = section("code")?.get("inlinePaddingDp")
                ?.resolveTheme(isDark).asPadding(MarkdownConfig.CodeConfig().inlinePaddingDp),
        ),
        quote = MarkdownConfig.QuoteConfig(
            color = section("quote")?.get("color")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.QuoteConfig().color),
            widthDp = section("quote")?.get("widthDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.QuoteConfig().widthDp),
        ),
        link = MarkdownConfig.LinkConfig(
            color = section("link")?.get("color")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.LinkConfig().color),
            underlined = section("link")?.get("underlined")
                ?.resolveTheme(isDark).asBoolean(MarkdownConfig.LinkConfig().underlined),
        ),
        heading = MarkdownConfig.HeadingConfig(
            sizeMultipliers = section("heading")?.get("sizeMultipliers")
                ?.resolveTheme(isDark).asFloatList(MarkdownConfig.HeadingConfig().sizeMultipliers),
        ),
        thematicBreak = MarkdownConfig.ThematicBreakConfig(
            color = section("thematicBreak")?.get("color")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.ThematicBreakConfig().color),
            heightDp = section("thematicBreak")?.get("heightDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.ThematicBreakConfig().heightDp),
        ),
        table = MarkdownConfig.TableConfig(
            cornerRadiusDp = section("table")?.get("cornerRadiusDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.TableConfig().cornerRadiusDp),
            headerBackground = section("table")?.get("headerBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.TableConfig().headerBackground),
            rowAltBackground = section("table")?.get("rowAltBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.TableConfig().rowAltBackground),
            borderColor = section("table")?.get("borderColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.TableConfig().borderColor),
            borderWidthDp = section("table")?.get("borderWidthDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.TableConfig().borderWidthDp),
            cellPaddingDp = section("table")?.get("cellPaddingDp")
                ?.resolveTheme(isDark).asPadding(MarkdownConfig.TableConfig().cellPaddingDp),
            copyButtonColor = section("table")?.get("copyButtonColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.TableConfig().copyButtonColor),
        ),
        mermaid = MarkdownConfig.MermaidConfig(
            theme = section("mermaid")?.get("theme")
                ?.resolveTheme(isDark).asString(MarkdownConfig.MermaidConfig().theme),
            cornerRadiusDp = section("mermaid")?.get("cornerRadiusDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.MermaidConfig().cornerRadiusDp),
            blockBackground = section("mermaid")?.get("blockBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.MermaidConfig().blockBackground),
        ),
        image = MarkdownConfig.ImageConfig(
            cornerRadiusDp = section("image")?.get("cornerRadiusDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.ImageConfig().cornerRadiusDp),
            borderWidthDp = section("image")?.get("borderWidthDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.ImageConfig().borderWidthDp),
            borderColor = section("image")?.get("borderColor")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.ImageConfig().borderColor),
            maxHeightDp = section("image")?.get("maxHeightDp")
                ?.resolveTheme(isDark).asFloat(MarkdownConfig.ImageConfig().maxHeightDp),
            loadingBackground = section("image")?.get("loadingBackground")
                ?.resolveTheme(isDark).asColor(MarkdownConfig.ImageConfig().loadingBackground),
            errorText = section("image")?.get("errorText")
                ?.resolveTheme(isDark).asString(MarkdownConfig.ImageConfig().errorText),
        ),
    )
}

// ── 递归转换：Rhino 对象 → Kotlin Map / List ──────────────────────────────

private fun toMap(value: Any?): Map<String, Any?>? = when (value) {
    is NativeObject -> {
        val map = LinkedHashMap<String, Any?>()
        for (id in value.allIds) {
            val key = id.toString()
            map[key] = toPlain(value.get(key, value))
        }
        map
    }
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toPlain(v) }
    else -> null
}

private fun toList(value: Any?): List<Any?>? = when (value) {
    is NativeArray -> {
        val list = ArrayList<Any?>(value.size)
        for (i in 0 until value.size) {
            list += toPlain(value.get(i, value))
        }
        list
    }
    is List<*> -> value.map { toPlain(it) }
    else -> null
}

/** 把 Rhino 值转成纯 Kotlin 值（Map / List / 基本类型 / null） */
private fun toPlain(value: Any?): Any? = when (value) {
    is NativeObject -> toMap(value)
    is NativeArray -> toList(value)
    is Map<*, *> -> toMap(value)
    is List<*> -> toList(value)
    is org.mozilla.javascript.Undefined -> null
    else -> value
}

// ── 主题感知 ──────────────────────────────────────────────────────────────

/** 若值是 `{ light: X, dark: Y }` 则按当前主题选取，否则原样返回 */
private fun Any?.resolveTheme(isDark: Boolean): Any? {
    val map = toMap(this) ?: return this
    if (!map.containsKey("light") && !map.containsKey("dark")) return this
    val key = if (isDark) "dark" else "light"
    if (map.containsKey(key)) return map[key]
    return map[if (isDark) "light" else "dark"]
}

// ── 取值助手 ──────────────────────────────────────────────────────────────

private fun Any?.asFloat(default: Float): Float = when (this) {
    is Number -> this.toFloat()
    is String -> this.toFloatOrNull() ?: default
    else -> default
}

private fun Any?.asString(default: String): String = when (this) {
    is String -> this
    else -> default
}

private fun Any?.asInt(default: Int): Int = when (this) {
    is Number -> this.toInt()
    is String -> this.toIntOrNull() ?: default
    is Boolean -> if (this) 1 else 0
    else -> default
}

private fun Any?.asBoolean(default: Boolean): Boolean = when (this) {
    is Boolean -> this
    is Number -> this.toInt() != 0
    is String -> when (this.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }
    else -> default
}

private fun Any?.asColor(default: String?): String? = when (this) {
    is String -> this.takeIf { it.matches(Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")) } ?: default
    else -> default
}

private fun Any?.asPadding(default: MarkdownConfig.Padding): MarkdownConfig.Padding {
    val map = toMap(this) ?: return default
    return MarkdownConfig.Padding(
        left = map["left"].asFloat(default.left),
        top = map["top"].asFloat(default.top),
        right = map["right"].asFloat(default.right),
        bottom = map["bottom"].asFloat(default.bottom),
    )
}

private fun Any?.asFloatList(default: List<Float>): List<Float> {
    val list = toList(this) ?: return default
    if (list.isEmpty()) return default
    return list.mapIndexed { index, item ->
        item.asFloat(default.getOrElse(index) { default.last() })
    }
}
