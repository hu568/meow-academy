# 🐾 喵仓可编辑配置地图

> 版本：2026-08-24T12:00:00Z

本文件是 App 内所有「文本动态配置」的**权威使用说明**（可编辑地图）。
`appconfig/README.md` 是本文件的同步副本（App 更新时**强制覆盖**，不保留本地修改）。

## 文件清单

| 文件 | 可修改项 | 可改 | 说明 |
|---|---|---|---|
| `markdown-config.jsonc` | 43 | ✅ | Markdown 渲染样式（公式 / 列表 / 代码 / 引用 / 链接 / 标题 / 分割线 / 表格 / mermaid / 图片） |

## 目录职责

- **`config-defaults/` = 默认模板（只读）**：
  - 本目录由 App 在**APK 升级或同版本重装**时从内置 assets 自动同步（`.sync-token` 判断），开发者也在这里维护默认值；
  - 内置 agent（DSH/AI）**可读、可复制，但不可修改**；
  - 想改默认模板 = 改 APK 源码 `android-app/app/src/main/assets/config-defaults/` 后重新打包。
- **`appconfig/` = 用户文件（可改）**：
  - 用户 / AI 直接在这里改值，App 升级**永不覆盖**；
  - 默认状态下，`appconfig/markdown-config.jsonc` 是 `config-defaults/markdown-config.jsonc` 的**完整副本**（含 `version` 与 `_editableCount`）。

## 怎么改外观（示例）

1. 用 DSH/AI 的 `write` 工具把 `appconfig/markdown-config.jsonc` 里的 `list.bulletWidthDp` 从 `6` 改成 `12`；
2. FileObserver 热更，无需重启 App，即时生效；
3. 只写想改的键即可（没写的键自动用默认模板的值）；`null` 表示「回退默认」。

## 如何恢复默认

- 把 `config-defaults/markdown-config.jsonc` **复制覆盖**到 `appconfig/markdown-config.jsonc`（文件保持存在，内容回到当前默认，`version` 也同步更新）；
- 只清文本配置（`appconfig/*.jsonc`），**保留资源文件**（`appconfig/fonts/`、`images/`、`audio/` 等）。

## 配置格式约定

- 每个 JSONC 文件都是**严格 JSON + `//`、`/* */` 注释**，不能有尾逗号、不能写 JS 表达式；
- 顶层元数据：`"version"`（ISO 8601 时间戳）+ `"_editableCount"`（可修改项数，`_` 前缀 = 元数据，解析时忽略）；
- 主题感知字段：`{ "light": X, "dark": Y }`，缺省一侧回退另一侧；
- 长度单位一律 dp（数字），颜色 `#RRGGBB` / `#AARRGGBB`；
- 数组整体替换（不按索引合并）；嵌套对象递归合并。
