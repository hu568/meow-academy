# 版本更新记录

## [0.2.2] - 2026-08-23（debug）

第四个 debug 包（约 86MB）。核心变更：**HTML 文件 WebView 预览、Markdown 块级渲染 + Mermaid、聊天右侧功能看板**（快速换模型 / 快捷附加文件 / 调用量统计）。

**新特性：**

- ✨ **HTML 文件 WebView 预览**：文件管理点击 `.html/.htm/.xhtml` 直接进入统一编辑器，默认预览模式渲染（磁盘态 `loadUrl(file://…)` / 编辑态 `loadDataWithBaseURL`），支持 JS/CSS/相对资源；安全上关闭 file URL 间越权访问
- ✨ **M5 Markdown 块级渲染**：表格、代码块圆角 + 复制按钮、公式块圆角背景、引用 / 链接 / 标题 / 分割线等外观全面升级，支持通过 `appconfig/markdown-config.js` 动态调节
- ✨ **Mermaid 图表**：`mermaid.min.js` 内置，流程图 / 时序图 / 甘特图等支持圆角、交互、暗色适配与甘特图优化
- ✨ **聊天右侧功能看板**：快速切换模型、快捷附加文件、调用量统计（轮数 / 步骤 / LLM 时长 / 工具时长 / 首 token / token 用量 / 缓存命中 / 上下文使用率）
- ✨ **`session/stats` RPC**：DSH 侧会话调用量统计（折叠口径见 `docs/notes/chat-dashboard-usage-stats.md`），杀 App 重开 resume 后统计仍完整；runtime.bin 已含此改动
- ✨ **HTML 预览文档与交互修复**：右侧看板半透明圆角统一、内容区左滑打开 / 面板右滑收回、修复左抽屉手势冲突

**本包修复：**

- 🔧 右侧功能看板交互：去黑遮罩、`X` 左 / `⋮` 右、`⋮` 菜单切换、右缘滑出、状态栏对齐
- 🔧 修复 HTML 预览在统一编辑器中的预览 / 编辑切换与保存联动

**⚠️ 升级须知：**

- runtime.bin 已随本包更新（含 `session/stats` RPC），无需手动重建；若调用量面板显示空态，先确认 App 内置运行时已初始化完成
- 真机验收：HTML 预览 ✓ Markdown 表格/公式/代码复制 ✓ Mermaid 渲染 ✓ 右侧看板三块面板（模型 / 文件 / 统计）请按需复测

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.1...v0.2.2