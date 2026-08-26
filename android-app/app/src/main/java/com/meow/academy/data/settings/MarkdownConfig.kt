package com.meow.academy.data.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * Markdown 渲染配置（appconfig/markdown-config.jsonc 解析结果）。
 *
 * 两层结构：
 * - [MarkdownConfigRaw]：JSONC 解析 + 深合并后的原始 Map（未解析 {light, dark} 主题对象）；
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
 * 把任意值转为原始配置 Map。
 *
 * @param raw 来自 [parseConfigJsonc] 的 Map 或嵌套值
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

// ── JSONC 带注释输出（旧 JS 迁移用，docs/design-dynamic-config.md §3/§5） ──

private val MARKDOWN_SECTION_HEADERS: Map<String, String> = mapOf(
    "formula" to "公式块（$$…$$）",
    "list" to "无序列表「-」渲染的 · 大小",
    "code" to "代码（``` 围栏块 + 行内 `code`）",
    "quote" to "引用块",
    "link" to "链接",
    "heading" to "标题（H1..H6）",
    "thematicBreak" to "水平分割线",
    "table" to "表格",
    "mermaid" to "Mermaid 图",
    "image" to "图片（md 渲染 + 聊天图片）",
)

private val MARKDOWN_FIELD_COMMENTS: Map<String, Map<String, String>> = mapOf(
    "formula" to mapOf(
        "blockCornerRadiusDp" to "背景圆角 (dp)，0 = 直角",
        "blockBackground" to "浅色浅灰 / 深色深灰，null = 无背景（主题感知）",
        "blockPaddingDp" to "内边距 (dp)：{ left, top, right, bottom }",
        "blockFitCanvas" to "是否撑满容器宽度",
        "blockAlign" to "0=左 1=中 2=右",
        "blockTextColor" to "公式块文字色，null = 跟随主题（主题感知）",
        "inlineTextColor" to "行内公式文字色，null = 跟随主题（主题感知）",
    ),
    "list" to mapOf(
        "bulletWidthDp" to "· 的直径 (dp)",
        "bulletStrokeWidthDp" to "描边宽 (dp)",
        "itemColor" to "项目颜色，null = 跟随主题（主题感知）",
    ),
    "code" to mapOf(
        "blockCornerRadiusDp" to "代码块背景圆角 (dp)，0 = 直角",
        "blockBackground" to "代码块背景色，null = 主题默认（主题感知）",
        "blockMarginDp" to "代码块外边距 (dp)",
        "blockTextSizeRatio" to "代码块内文字相对正文比例",
        "textSizeRatio" to "行内代码相对正文比例",
        "blockTextColor" to "代码块文字色，null = 主题默认（主题感知）",
        "textColor" to "行内代码文字色，null = 主题默认（主题感知）",
        "inlineCornerRadiusDp" to "行内代码背景圆角 (dp)，0 = 恢复 Markwon 直角",
        "inlineBackground" to "行内代码背景色，null = 主题默认（当前文字色 10% 透明度）（主题感知）",
        "inlinePaddingDp" to "行内代码内边距 (dp)：{ left, top, right, bottom }",
    ),
    "quote" to mapOf(
        "color" to "左侧竖线颜色，null = 主题默认（主题感知）",
        "widthDp" to "左侧竖线宽 (dp)",
    ),
    "link" to mapOf(
        "color" to "链接颜色，null = 主题默认（主题感知）",
        "underlined" to "是否下划线",
    ),
    "heading" to mapOf(
        "sizeMultipliers" to "H1..H6 相对正文倍率数组（6 个 float）",
    ),
    "thematicBreak" to mapOf(
        "color" to "分割线颜色，null = 主题默认（主题感知）",
        "heightDp" to "分割线高 (dp)",
    ),
    "table" to mapOf(
        "cornerRadiusDp" to "表格圆角 (dp)",
        "headerBackground" to "表头背景色，null = 主题默认（主题感知）",
        "rowAltBackground" to "斑马纹背景，null = 无（主题感知）",
        "borderColor" to "边框颜色，null = 主题默认（主题感知）",
        "borderWidthDp" to "边框宽 (dp)",
        "cellPaddingDp" to "单元格内边距 (dp)：{ left, top, right, bottom }",
        "copyButtonColor" to "复制按钮颜色，null = 主题默认（主题感知）",
    ),
    "mermaid" to mapOf(
        "theme" to "'' = 自动跟随系统深色，'dark'/'default'/'neutral'/'forest'/'base'",
        "cornerRadiusDp" to "图块背景圆角 (dp)，0 = 直角",
        "blockBackground" to "图块背景色，null = 主题默认（surfaceVariant 半透明）（主题感知）",
    ),
    "image" to mapOf(
        "cornerRadiusDp" to "图片圆角 (dp)，0 = 直角",
        "borderWidthDp" to "线框宽 (dp)，0 = 无边框",
        "borderColor" to "线框颜色，null = 跟随主题（主题感知）",
        "maxHeightDp" to "聊天气泡内图片最大高度 (dp)",
        "loadingBackground" to "加载中背景色，null = 主题默认（surfaceVariant 半透明）（主题感知）",
        "errorText" to "加载失败提示文案",
    ),
)

/**
 * 把含有 [values] 的配置 Map 格式化为带注释的 JSONC 文本。
 *
 * 每个字段后面都有注释（来自 [MARKDOWN_FIELD_COMMENTS]），
 * 每段前面有 Section 标题注释，顶部有统一文件头。
 * 用于旧 JS 迁移时生成可读性好的用户文件。
 */
fun formatMarkdownConfigJsonc(
    values: Map<String, Any?>,
    version: String,
    editableCount: Int,
): String {
    val sb = StringBuilder()
    sb.append("// 🐾 喵仓 Markdown 渲染配置\n")
    sb.append("// 使用说明见 appconfig/README.md\n")
    sb.append("// 可修改项：$editableCount · 版本：$version\n")
    sb.append("{\n")
    sb.append("  \"version\": ").append(JSONObject.quote(version)).append(",\n")
    sb.append("  \"_editableCount\": ").append(editableCount).append(",\n")
    val sections = MARKDOWN_FIELD_COMMENTS.toList()
    for (sectionIndex in sections.indices) {
        val (section, comments) = sections[sectionIndex]
        val header = MARKDOWN_SECTION_HEADERS[section] ?: section
        sb.append('\n')
        sb.append("  // ── $header ──\n")
        sb.append("  \"").append(section).append("\": {\n")
        val sectionValues = values[section] as? Map<*, *> ?: emptyMap<String, Any?>()
        val fields = comments.toList()
        for (fieldIndex in fields.indices) {
            val (field, comment) = fields[fieldIndex]
            val value = sectionValues[field]
            val json = jsonValueToString(value)
            val comma = if (fieldIndex < fields.size - 1) "," else ""
            sb.append("    \"").append(field).append("\": ").append(json).append(comma)
            sb.append("  // ").append(comment).append('\n')
        }
        sb.append("  }")
        if (sectionIndex < sections.size - 1) sb.append(',')
        sb.append('\n')
    }
    sb.append("}\n")
    return sb.toString()
}

private fun jsonValueToString(value: Any?): String = when (value) {
    null -> "null"
    is String -> JSONObject.quote(value)
    is Boolean, is Number -> value.toString()
    is Map<*, *> -> JSONObject(value).toString()
    is List<*> -> JSONArray(value).toString()
    else -> JSONObject.quote(value.toString())
}
