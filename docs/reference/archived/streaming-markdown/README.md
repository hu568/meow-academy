# 🗄️ 归档：流式 Markdown「半增量渲染」方案

**归档日期**：2026-09-09
**来源**：`android-app/app/src/main/java/com/meow/academy/ui/chat/`
**状态**：已移出编译源集，不参与构建，仅作历史参考（恢复方式见文末）。

## 这是什么

M5 时期（2026-08-21 前后，commit `0164ac5` / `4b4d1d4`）实现的第一版流式 Markdown 渲染：

| 文件 | 职责 |
|---|---|
| `MarkdownStreaming.kt` | 纯函数拆分器 `splitStreamingBlocks()` → `StreamingBlocks(stable, active)` |
| `StreamingMarkdownRenderer.kt` | `StreamingMarkdownRenderer`：稳定块 Spanned LRU 缓存 + 稳定前缀复用 + 活动块重渲染 |

思路是「块级增量 + 块内行内重渲染」（参考 semidown / MarkdownDisplayView）：
稳定块只解析一次并缓存，只有正在增长的最后一个块（活动块）每帧重渲染。

## 为什么归档

现行方案改成了 **Compose 块级列表渲染**，两件事都被取代：

| 归档文件的职责 | 现行实现 |
|---|---|
| 把流式文本拆成稳定块 / 活动块 | `MarkdownBlocks.kt` 的 `parseMarkdownBlocks()` → `MdBlock`（段落 / 围栏 / 表格 / 数学块 / mermaid / 图片，带 `closed` 流式态） |
| 稳定块 Spanned LRU 缓存 + 稳定前缀拼接 | `MarkdownText.kt` 里 `key(block)`——`MdBlock` 是 data class，内容不变则 key 不变，稳定块天然不重组、不重建 |
| 流式刷新节流 | `MarkdownText.kt` 的 `STREAMING_RENDER_INTERVAL_MS = 50L`（约 20fps） |
| 流式表格不跳动 | `StreamingTable.kt` + `MarkdownTable.kt`（Compose 原生定宽表格，避免 Markwon TableRowSpan 的「塌缩 → 弹起」） |

归档前已确认全仓库无生产引用（仅有 `MarkdownStreamingTest.kt` 一个测试引用 `splitStreamingBlocks`）。

## ⚠️ 恢复前必读

1. **`isTableDelimiter` 没有一起归档**：它当时被活代码引用（`MarkdownBlocks.kt`、`StreamingTable.kt`），已迁到
   `android-app/app/src/main/java/com/meow/academy/ui/chat/StreamingTable.kt`（本归档副本中已移除，文件头有说明）。
   恢复 `MarkdownStreaming.kt` 时需从那里取回，避免重复定义。
2. **测试也拆了**：拆分器用例在 `MarkdownStreamingTest.kt`（本目录，不编译）；表格与分隔行用例留在活测试
   `app/src/test/java/com/meow/academy/ui/chat/StreamingTableTest.kt`。
3. 包名仍是 `com.meow.academy.ui.chat`——要恢复就把 `.kt` 文件放回该包路径并删掉文件头「已归档」注释。

## 文件清单

- `MarkdownStreaming.kt`（拆分器；`isTableDelimiter` 已迁出）
- `StreamingMarkdownRenderer.kt`（Spanned 缓存渲染器）
- `MarkdownStreamingTest.kt`（拆分器单测，随归档一起冻结）

---
*由樱茈整理喵 🐾*
