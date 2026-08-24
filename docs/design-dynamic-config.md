# 🐾 喵仓动态配置 JSONC 格式规范

> 日期：2026-08-24 · 状态：草案 · 对应探索笔记：`docs/notes/meow-dynamic-architecture-note.md` §11
> 本规范是**通用设计文档**，定义 App 内所有「文本动态配置」的 JSONC 文件格式、元数据、注释规范与 Kotlin 接口契约。
> 适用范围：`config-defaults/*.jsonc`（默认模板）与 `appconfig/*.jsonc`（用户文件）。
> 文中 Markdown 配置仅为**示例**，其他功能（typography、resources 等）按同一规范扩展。

---

## 1. 目录与职责

> 📌 路径说明（Android 安装后的实际数据路径）：
> - `appconfig/` = App 私有数据目录：`context.filesDir/appconfig/`（即 `/data/user/0/<包名>/files/appconfig/`），由 `RuntimeExtractor.appConfigDir()` 创建；
> - `config-defaults/` = App 私有数据目录：`context.filesDir/config-defaults/`；
> - `assets/config-defaults/` = **APK 内置 assets**（源码 `android-app/app/src/main/assets/config-defaults/`），构建时打进 APK，运行时只读，**不是手机数据目录**。

| 路径 | 职责 | 谁可修改 |
|---|---|---|
| `assets/config-defaults/*.jsonc` | APK 内置默认模板（只读） | 开发者 |
| `config-defaults/*.jsonc` | 默认模板运行时副本（AI 可读 / 可复制，**不可修改**） | 开发者（App 同步） |
| `config-defaults/README.md` | 可编辑地图 · 权威版（各文件用途、可改/只读、操作说明） | 开发者（App 同步） |
| `appconfig/*.jsonc` | 用户文件（默认 = 模板完整副本，用户/AI 直接改值） | 用户 / AI |
| `appconfig/README.md` | `config-defaults/README.md` 的同步副本（AI 在 appconfig 内可直接读） | 开发者（App 同步，**更新强制覆盖**） |

规则：
- 默认模板与用户文件**文件名相同**，靠目录区分；
- `config-defaults/` 仅在 APK 升级或同版本重装时从 assets 同步（`.sync-token` 判断）；
- `appconfig/` 下的用户配置文件（`*.jsonc`）永不因升级被覆盖；**唯一例外是 `appconfig/README.md`**——它是同步副本，更新时**强制覆盖**（不保留本地修改）。

---

## 2. 元数据字段（所有 JSONC 配置必须包含）

```jsonc
{
  "version": "2026-08-24T12:00:00Z",
  "_editableCount": 43
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | string（ISO 8601 UTC 时间戳） | 配置版本；默认模板内容变化时更新时间戳，避免每次包更新手动 bump |
| `_editableCount` | int | 可修改项数量（叶子字段数）；`_` 前缀 = 元数据，解析时忽略 |

约定：
- `_` 前缀字段一律视为元数据，**不参与深合并、不进入 Kotlin 配置对象**；
- 默认状态：`appconfig/*.jsonc` 是 `config-defaults/*.jsonc` 的**完整副本**（含 `version` 与 `_editableCount`），两者内容相同；
- 用户文件里的 `version` 记录「这份文件基于哪个模板版本」，解析时忽略，可作版本提示/迁移参考；
- `_editableCount` 不校验准确性，仅作展示/提示用途。

---

## 3. 文件头注释模板（统一）

所有 JSONC 配置文件（默认模板与用户文件）使用**同一套**文件头注释，不区分两版：

```jsonc
// 🐾 喵仓 <功能名> 配置
// 使用说明见 appconfig/README.md
// 可修改项：43 · 版本：2026-08-24T12:00:00Z
```

约定：
- 文件头只保留「功能名 + 指向 README + 元数据」，**不写两套说明**；
- 默认模板 / 用户文件的区别、复制覆盖、恢复默认等操作说明，统一写在 `config-defaults/README.md`（可编辑地图）；
- `appconfig/README.md` 是 `config-defaults/README.md` 的同步副本，AI 在 `appconfig/` 内可直接读；文件头里**显式写 `appconfig/README.md`**，不写相对模糊的 `README.md`；
- 每次新建配置文件时，文件头模板直接复制，只需改功能名、可修改项数量、版本。

> 📌 `config-defaults/README.md` 是唯一权威使用说明，包含：各文件用途、可改/只读、如何复制到 `appconfig/`、如何恢复默认。`appconfig/README.md` 随 config-defaults 同步，更新时**强制覆盖**。

### 3.1 README 元数据

`README.md` 顶部带元数据块，与各 JSONC 的 `version` / `_editableCount` 绑定：

```markdown
# 🐾 喵仓可编辑配置地图

> 版本：2026-08-24T12:00:00Z

| 文件 | 可修改项 | 可改 | 说明 |
|---|---|---|---|
| `markdown-config.jsonc` | 43 | ✅ | Markdown 渲染样式（默认模板在 `config-defaults/`，用户文件在 `appconfig/`） |
| `typography.jsonc` | TBD | ✅ | 字体与字号 |
```

约定：
- README 的「版本」 = 当前配置地图版本，与默认模板最新 `version` 一致；
- 「可修改项」来自各 JSONC 的 `_editableCount`，制作/更新配置文件时同步维护；
- AI 先读 README 元数据表，就能知道有哪些文件、各能改多少项、默认/用户文件分别在哪。

---

## 4. 通用字段约定

| 约定 | 规则 |
|---|---|
| 主题感知 | 需要深浅色分别取值时写 `{ "light": X, "dark": Y }`；缺省一侧回退另一侧 |
| `null` | 表示「回退默认」，与「没写」等价 |
| 长度单位 | 一律用 dp（数字） |
| 颜色格式 | `#RRGGBB` 或 `#AARRGGBB` |
| 数组 | 整体替换（不按索引合并） |
| 嵌套对象 | 递归合并 |
| 字符串 | 普通字符串 |

---

## 5. 注释规范

### 5.1 Section 注释

每个功能分区上方用分隔线注释：

```jsonc
// ── 公式块（$$…$$） ──────────────────────────────
```

### 5.2 可修改项注释

每项在字段上方或行尾写注释，包含：作用、单位、特殊取值、是否主题感知。

```jsonc
"blockCornerRadiusDp": 12,   // 背景圆角 (dp)，0 = 直角
"blockBackground": {          // 浅色浅灰 / 深色深灰，null = 无背景（主题感知）
  "light": "#F2F2F7",
  "dark": "#1E1E2E"
}
```

### 5.3 注释目标

- 给 AI 的提示：让它知道这个键控制什么、能不能改、怎么改；
- 给开发者的提示：类型、默认值、单位、边界。

---

## 6. 数据结构定义规范（通用）

每个 JSONC 配置文件的顶层结构遵循同一模式：

```
{
  "version": "…",           // 元数据：时间戳
  "_editableCount": N,      // 元数据：可修改项数（_ 前缀，解析忽略）
  "<section1>": {           // 功能分区
    "<field1>": <value>,    // 可修改项（叶子字段）
    "<field2>": <value>,    // 类型、默认值、主题感知由各功能自行定义
    …
  },
  "<section2>": { … },
  …
}
```

- **section** = 按功能分组（如 `formula`、`code`、`table`），对应 Kotlin 数据类的嵌套子类；
- **leaf field** = 可修改项（数字 / 字符串 / 布尔 / 数组 / `null` / 主题对象 `{ light, dark }`）；
- 每个功能的实际字段列表由对应的 Kotlin 数据类与解析器定义，**本规范不硬编码**。

> 以下 §7 以当前已实现的 Markdown 渲染配置为例，展示具体字段定义。其他功能（typography、resources 等）的字段定义将作为独立章节或文件，按此模式新增。

---

## 7. 示例：Markdown 配置数据结构（当前已开放项）

> 对应 Kotlin：`MarkdownConfig.kt` / `resolveMarkdownConfig()`。
> 当前 `_editableCount = 43`。

### 7.1 formula 公式块

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `blockCornerRadiusDp` | float | 12 | 否 | 背景圆角 (dp)，0 = 直角 |
| `blockBackground` | color/null | null | 是 | 公式块背景色，null = 无背景 |
| `blockPaddingDp` | object | `{left:16, top:8, right:16, bottom:8}` | 否 | 内边距 |
| `blockFitCanvas` | bool | true | 否 | 是否撑满容器宽度 |
| `blockAlign` | int | 1 | 否 | 0=左 1=中 2=右 |
| `blockTextColor` | color/null | null | 是 | 公式块文字色 |
| `inlineTextColor` | color/null | null | 是 | 行内公式文字色 |

### 7.2 list 无序列表

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `bulletWidthDp` | float | 6 | 否 | `-` 渲染的 `·` 直径 (dp) |
| `bulletStrokeWidthDp` | float | 1 | 否 | 描边宽 (dp) |
| `itemColor` | color/null | null | 是 | 项目颜色，null = 跟随主题 |

### 7.3 code 代码

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `blockCornerRadiusDp` | float | 10 | 否 | 代码块圆角 (dp)，0 = 直角 |
| `blockBackground` | color/null | null | 是 | 代码块背景色 |
| `blockMarginDp` | float | 8 | 否 | 代码块外边距 (dp) |
| `blockTextSizeRatio` | float | 0.85 | 否 | 代码块文字相对正文比例 |
| `textSizeRatio` | float | 0.85 | 否 | 行内代码相对正文比例 |
| `blockTextColor` | color/null | null | 是 | 代码块文字色 |
| `textColor` | color/null | null | 是 | 行内代码文字色 |
| `inlineCornerRadiusDp` | float | 6 | 否 | 行内代码圆角 (dp)，0 = Markwon 直角 |
| `inlineBackground` | color/null | null | 是 | 行内代码背景色 |
| `inlinePaddingDp` | object | `{left:5, top:2, right:5, bottom:2}` | 否 | 行内代码内边距 |

### 7.4 quote 引用块

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `color` | color/null | null | 是 | 左侧竖线颜色 |
| `widthDp` | float | 4 | 否 | 左侧竖线宽 (dp) |

### 7.5 link 链接

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `color` | color/null | null | 是 | 链接颜色 |
| `underlined` | bool | true | 否 | 是否下划线 |

### 7.6 heading 标题

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `sizeMultipliers` | float[] | `[1.6, 1.4, 1.25, 1.15, 1.1, 1.0]` | 否 | H1..H6 相对正文倍率 |

### 7.7 thematicBreak 分割线

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `color` | color/null | null | 是 | 分割线颜色 |
| `heightDp` | float | 2 | 否 | 分割线高 (dp) |

### 7.8 table 表格

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `cornerRadiusDp` | float | 12 | 否 | 表格圆角 (dp) |
| `headerBackground` | color/null | null | 是 | 表头背景色 |
| `rowAltBackground` | color/null | null | 是 | 斑马纹背景，null = 无 |
| `borderColor` | color/null | null | 是 | 边框颜色 |
| `borderWidthDp` | float | 1 | 否 | 边框宽 (dp) |
| `cellPaddingDp` | object | `{left:10, top:6, right:10, bottom:6}` | 否 | 单元格内边距 |
| `copyButtonColor` | color/null | null | 是 | 复制按钮颜色 |

### 7.9 mermaid 图

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `theme` | string | `""` | 否 | `''`=跟随系统，`dark`/`default`/`neutral`/`forest`/`base` |
| `cornerRadiusDp` | float | 12 | 否 | 图块圆角 (dp) |
| `blockBackground` | color/null | null | 是 | 图块背景色 |

### 7.10 image 图片

| 字段 | 类型 | 默认 | 主题感知 | 说明 |
|---|---|---|---|---|
| `cornerRadiusDp` | float | 12 | 否 | 图片圆角 (dp) |
| `borderWidthDp` | float | 1 | 否 | 线框宽 (dp)，0 = 无边框 |
| `borderColor` | color/null | null | 是 | 线框颜色 |
| `maxHeightDp` | float | 320 | 否 | 聊天气泡内图片最大高度 (dp) |
| `loadingBackground` | color/null | null | 是 | 加载中背景色 |
| `errorText` | string | `"图片加载失败"` | 否 | 加载失败提示文案 |

---

## 8. Kotlin 接口契约（通用）

### 8.1 通用管道（所有 JSONC 配置共用）

```kotlin
// 1. JSONC 文本 → 纯 JSON（剥注释，字符串内 // 与 /* */ 不动）
fun stripJsonc(text: String): String

// 2. 纯 JSON → 原始 Map（剔除 _ 前缀元数据）
fun parseConfigJsonc(text: String): Map<String, Any?>

// 3. 深合并（对象递归；数组/标量整体替换；null 视为无覆盖）
fun deepMerge(defaults: Map<String, Any?>, overrides: Map<String, Any?>): Map<String, Any?>
```

### 8.2 各功能解析器（按功能各自实现）

每个功能在自己的数据类中提供解析函数，将合并后的原始 Map 转为具体配置对象：

```kotlin
// 示例：Markdown 配置
fun resolveMarkdownConfig(raw: MarkdownConfigRaw?, isDark: Boolean): MarkdownConfig

// 示例：排版配置（后续）
// fun resolveTypographyConfig(raw: TypographyConfigRaw?, isDark: Boolean): TypographyConfig
```

### 8.3 回退链（通用）

```
用户文件缺失 / 为 null
        ↓
默认模板（config-defaults/）
        ↓
Kotlin 内置默认
```

- 用户文件不存在 / 解析失败 → 使用默认模板；
- 默认模板缺失字段 → 使用 Kotlin 数据类默认值；
- 解析失败时保留上次有效配置，首次失败用内置默认，不崩溃。

---

## 9. 新增 / 修改可修改项步骤（通用）

以 `<功能>` 代指具体功能（如 Markdown、typography、resources）。开发者 checklist：

- [ ] 在 `<功能>Config.kt` 数据类加字段 + Kotlin 默认值；
- [ ] 在 `<功能>Config` 的解析函数（如 `resolveMarkdownConfig()`）加解析；
- [ ] 在渲染/消费处应用新字段；
- [ ] 在 `config-defaults/<功能>-config.jsonc` 加字段 + 注释；
- [ ] 更新时间戳 `version` 与 `_editableCount`；
- [ ] 同步更新 `config-defaults/README.md`（文件清单 / 可修改项数）；
- [ ] 若本规范已有该功能的字段表格，同步更新对应章节。

删除可修改项同理，反向操作。

---

## 10. 追溯

- 本规范是**通用设计文档**，Markdown 配置（§7）仅为第一个落地示例；
- 对应探索笔记 §11（默认模板 + 用户文件 + JSONC + 恢复默认）；
- 资源热替换（字体/图片/音频/Lottie）见探索笔记 §5.2 / §5.3，不走本规范的深合并，走「覆盖优先」。
