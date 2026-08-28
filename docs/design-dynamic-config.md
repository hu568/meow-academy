# 🐾 喵仓动态配置 JSONC 格式规范

> 通用设计文档，定义 App 内所有「文本动态配置」的文件格式、注释规范与 Kotlin 接口契约。
> 适用范围：`config-defaults/*.jsonc`（默认模板）与 `appconfig/*.jsonc`（用户文件）。

## 1. 目录与职责

| 路径 | 职责 | 谁可修改 |
|---|---|---|
| `assets/config-defaults/*.jsonc` | APK 内置默认模板（只读） | 开发者 |
| `config-defaults/*.jsonc` | 默认模板运行时副本（**可读不可改**） | 开发者（App 同步） |
| `config-defaults/README.md` | 可编辑地图（权威使用说明） | 开发者（App 同步） |
| `appconfig/*.jsonc` | 用户文件（默认 = 模板完整副本，用户/AI 直接改值） | 用户 / AI |
| `appconfig/README.md` | config-defaults/README.md 的**同步副本**（更新强制覆盖） | 开发者（App 同步） |

> 升级规则：`config-defaults/` 在 APK 升级/同版本重装时从 assets 同步（`.sync-token` 判断）；
> `appconfig/*.jsonc` **永不因升级被覆盖**；`appconfig/README.md` 是唯一例外（同步副本强制覆盖）。

> 📌 **路径说明（Android 安装后的实际数据路径）**：
> - `appconfig/` = App 私有数据目录 `context.filesDir/appconfig/`（即 `/data/user/0/<包名>/files/appconfig/`），由 `RuntimeExtractor.appConfigDir()` 创建；
> - `config-defaults/` = App 私有数据目录 `context.filesDir/config-defaults/`；
> - `assets/config-defaults/` = **APK 内置 assets**（源码 `android-app/app/src/main/assets/config-defaults/`），构建时打进 APK，运行时只读，**不是手机数据目录**；
> - 升级/同步方向：`assets/` → 拷贝到 `config-defaults/`；`config-defaults/` → 首次/缺失时 seed 到 `appconfig/`。

## 2. 元数据字段

每个 JSONC 文件必须包含：
- **`"version"`**：ISO 8601 UTC 时间戳（如 `"2026-08-27T14:06:52Z"`），默认模板内容变化时更新；
  ⚠️ **必须 `date -u +%Y-%m-%dT%H:%M:%SZ` 取真实当前时间，严禁编造**。
- **`"_editableCount"`**：可修改项数（叶子字段数，`_` 前缀 = 元数据，**解析时忽略**，不校验准确性）。

## 3. 文件头注释模板

```jsonc
// 🐾 喵仓 <功能名> 配置
// 使用说明见 appconfig/README.md
// 可修改项：<N> · 版本：<ISO 8601 UTC>
```

> 功能名、可修改项数、版本随功能变化；其他内容固定。
> `appconfig/README.md` 是 `config-defaults/README.md` 的同步副本，强制覆盖。

## 4. 通用字段约定

| 约定 | 规则 |
|---|---|
| 主题感知 | `{ "light": X, "dark": Y }`，缺省一侧回退另一侧 |
| `null` | 回退默认（与「没写」等价） |
| 颜色 | `#RRGGBB` / `#AARRGGBB` |
| 长度单位 | dp（数字） |
| 数组 | 整体替换（不按索引合并） |
| 嵌套对象 | 递归合并 |

## 5. 注释规范

- 每段上方 `// ── 标题 ──`；每项行尾/上方写注释（作用、单位、是否主题感知）；
- 注释供 AI 参考，禁止注释/字符串混淆。

## 6. 数据结构定义（通用）

```
{ "version", "_editableCount", <section名>: { … } }
```

回退链：用户文件缺失/为 null → `config-defaults/` 默认模板 → Kotlin 数据类默认值。
解析失败：保留上次有效配置；首次用内置默认，不崩溃。

**各功能的字段定义以 `config-defaults/*.jsonc`（带注释默认模板）为唯一权威，本规范不重复字段表。**

## 7. Markdown 配置（`_editableCount = 43`）

```jsonc
{ "version", "_editableCount", formula, list, code, quote, link, heading, thematicBreak, table, mermaid, image }
```

> 字段/默认值/主题感知：见 `config-defaults/markdown-config.jsonc` 行内注释。
> 主题感知语义见 §4。

## 8. Kotlin 接口契约（通用）

### 8.1 JSONC 管道（所有配置共用）

```kotlin
fun stripJsonc(text: String): String         // 剥注释
fun parseConfigJsonc(text: String): Map       // 纯 JSON → Map（剔除 _ 前缀）
fun deepMerge(defaults, overrides): Map       // 对象递归；数组/标量替换；null=不覆盖
```

### 8.2 各功能解析器与仓库

```kotlin
// 数据类 + 解析函数 + 仓库（FileObserver + StateFlow + 深合并）
fun resolveMarkdownConfig(raw: MarkdownConfigRaw?, isDark: Boolean): MarkdownConfig
fun resolveThemeConfig(raw: ThemeConfigRaw?, isDark: Boolean): ThemeConfig

class MarkdownConfigRepository(context) : start() + config: StateFlow<MarkdownConfigRaw?>
class ThemeConfigRepository(context) : start() + config: StateFlow<ThemeConfigRaw?>
```

- 仓库职责：`config-defaults/` 同步（·sync-token）→ `appconfig/` seed 用户文件 → FileObserver 热更 → 深合并 → `StateFlow`；
- 各功能仓库各自独立，共用 JSONC 管道与 `syncConfigDefaultsIfNeeded()`（共用一个 `.sync-token`）。

## 9. 新增 / 修改可修改项步骤

- [ ] 在 `<功能>Config.kt` 数据类加字段 + Kotlin 默认值；
- [ ] 在解析函数加解析 + 在渲染/消费处应用；
- [ ] 在 `config-defaults/<功能>-config.jsonc` 加字段 + 注释；
- [ ] **先 `date -u +%Y-%m-%dT%H:%M:%SZ` 取真实 UTC 时间**，再更新 version（文件头 + `version` 字段 + README + Kotlin `DEFAULT_VERSION`）与 `_editableCount`；
- [ ] 同步更新 `config-defaults/README.md` + 对应章节字段表（若有）。

> ⚠️ 严禁编造版本号：`version` 必须来自 `date -u`（见 §2）。

## 10. 追溯

- 本规范为通用设计文档，Markdown 配置（§7）与主题颜色配置（§11）为当前落地示例；
- 对应探索笔记 §11（默认模板 + 用户文件 + JSONC + 恢复默认）；
- 资源热替换（字体/图片/音频/Lottie）见探索笔记 §5.2 / §5.3，不走本规范的深合并，走「覆盖优先」。

## 11. 主题颜色 + 聊天背景配置（`_editableCount = 50`）

功能：**①** 动态配置主题模式（`ThemeMode.CONFIG`）构建整套 `ColorScheme`，热更即时换肤，跟随系统深浅；
**②** 聊天背景动态配置（预设 + 当前选择）；**③** 组件级色槽（工具折叠条 / 文件快捷栏）。
> 字段细节以 `config-defaults/theme-config.jsonc` 注释为唯一权威。

### 11.1 顶层结构

```jsonc
{ "version", "_editableCount",
  "seed": null, "overrides": { … }, "backgrounds": { … }, "components": { … } }
```

### 11.2 ColorScheme 色槽白名单（25 个，`KNOWN_THEME_SLOTS`）

primary / onPrimary / primaryContainer / onPrimaryContainer / secondary / onSecondary / secondaryContainer / onSecondaryContainer / tertiary / onTertiary / tertiaryContainer / onTertiaryContainer / background / onBackground / surface / onSurface / surfaceVariant / onSurfaceVariant / surfaceTint / error / onError / errorContainer / onErrorContainer / outline / outlineVariant

> 支持主题感知 `{ light, dark }`；`ColorScheme.copy()` 覆盖，未覆盖回退种子派生。

### 11.3 组件级色槽白名单（5 个，`KNOWN_COMPONENT_SLOTS`）

- `toolGroupBackground` ← `secondaryContainer`（工具折叠条背景）
- `toolGroupContent` ← `onSecondaryContainer`（折叠条文字/图标）
- `toolStatusColor` ← `primary`（工具 ✓ 状态色）
- `quickBarColor` ← `primary`（文件面包屑导航/图标）
- `quickBarSelectedContainer` ← `primaryContainer`（快捷栏 FilterChip 选中容器）

> 动机：seed 派生 `secondary` 色相偏移（淡蓝→蓝紫），工具折叠条跟 `secondaryContainer` 会「紫」；components 可单独控制。

### 11.4 关键约定

- **聊天背景**：简单模式（固定 `appconfig/images/bg.jpg`，选择存 DataStore） / 动态模式（读 `backgrounds.active`，写回 JSONC）；自定义图片是**二进制资源**，必须放 `appconfig/images/`（覆盖优先）。
- **覆盖优先级**：`overrides` 具体色槽 > 种子派生 > 内置默认；`seed` 没写回退 `#8A5CF6`（喵仓粉紫）。
- **解析/构建**：`resolveThemeConfig(raw, isDark)` → `ThemeConfig`；`buildConfigColorScheme(raw, isDark, fallbackSeedArgb)` → `ColorScheme`。
