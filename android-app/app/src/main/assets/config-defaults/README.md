# 🐾 喵仓可编辑配置地图

> 版本：2026-08-27T14:06:52Z

本文件是 App 内所有「文本动态配置」的**权威使用说明**（可编辑地图）。
`appconfig/README.md` 是本文件的同步副本（App 更新时**强制覆盖**，不保留本地修改）。

## 文件清单

| 文件 | 可修改项 | 可改 | 说明 |
|---|---|---|---|
| `markdown-config.jsonc` | 43 | ✅ | Markdown 渲染样式（公式 / 列表 / 代码 / 引用 / 链接 / 标题 / 分割线 / 表格 / mermaid / 图片） |
| `theme-config.jsonc` | 50 | ✅ | 主题颜色 + 聊天背景动态配置（种子色 + 具体色槽双重覆盖，背景预设 / 当前选择，组件级色槽，FileObserver 热更即时换肤） |

## 目录职责

- **`config-defaults/` = 默认模板（只读）**：
  - 本目录由 App 在**APK 升级或同版本重装**时从内置 assets 自动同步（`.sync-token` 判断），开发者也在这里维护默认值；
  - 内置 agent（DSH/AI）**可读、可复制，但不可修改**；
  - 想改默认模板 = 改 APK 源码 `android-app/app/src/main/assets/config-defaults/` 后重新打包。
- **`appconfig/` = 用户文件（可改）**：
  - 用户 / AI 直接在这里改值，App 升级**永不覆盖**；
  - 默认状态下，`appconfig/markdown-config.jsonc` 是 `config-defaults/markdown-config.jsonc` 的**完整副本**（含 `version` 与 `_editableCount`）。

## 二进制资源（图片 / 字体 / 音频）

- **二进制资源不走 JSONC 深合并**，走「覆盖优先」：必须**复制 / 移动到 `appconfig/` 的对应子文件夹**，App 优先用用户文件，缺失才回退内置默认；
- 聊天背景图片目录：`appconfig/images/`；
  - 设置页**简单模式（不勾选动态配置）**：固定文件名 `bg.jpg`，替换图片 = 直接覆盖 `appconfig/images/bg.jpg`；
  - **动态配置模式（勾选）**：AI / 用户可放任意文件名（如 `appconfig/images/aurora.png`），在 `theme-config.jsonc` 的 `backgrounds.active` 写 `file:images/aurora.png`；
- 恢复默认只清 `appconfig/*.jsonc` 文本配置，**保留** `appconfig/images/` 等资源文件。

## 怎么改外观（示例）

1. 用 DSH/AI 的 `write` 工具把 `appconfig/markdown-config.jsonc` 里的 `list.bulletWidthDp` 从 `6` 改成 `12`；
2. FileObserver 热更，无需重启 App，即时生效；
3. 只写想改的键即可（没写的键自动用默认模板的值）；`null` 表示「回退默认」。

### 怎么用动态配置换聊天背景（示例）

1. 设置 → 聊天背景 → 打开「使用动态配置」；
2. 用 DSH/AI 把图片文件放进 `appconfig/images/`（如 `aurora.png`）；
3. 改 `appconfig/theme-config.jsonc` 的 `backgrounds.active` 为 `"file:images/aurora.png"`（或 `"preset:自定义id"`），FileObserver 热更即时生效；
4. 也可以在 `backgrounds.presets` 里自定义渐变预设（数组整体替换，留空 = 用内置 6 个）。

## 如何恢复默认

- 把 `config-defaults/markdown-config.jsonc` **复制覆盖**到 `appconfig/markdown-config.jsonc`（文件保持存在，内容回到当前默认，`version` 也同步更新）；
- 只清文本配置（`appconfig/*.jsonc`），**保留资源文件**（`appconfig/fonts/`、`images/`、`audio/` 等）。

## 配置格式约定

- 每个 JSONC 文件都是**严格 JSON + `//`、`/* */` 注释**，不能有尾逗号、不能写 JS 表达式；
- 顶层元数据：`"version"`（ISO 8601 时间戳）+ `"_editableCount"`（可修改项数，`_` 前缀 = 元数据，解析时忽略）；
- 主题感知字段：`{ "light": X, "dark": Y }`，缺省一侧回退另一侧；
- 长度单位一律 dp（数字），颜色 `#RRGGBB` / `#AARRGGBB`；
- 数组整体替换（不按索引合并）；嵌套对象递归合并。
