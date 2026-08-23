# 版本更新记录

## [0.2.3] - 2026-08-24（debug）

第五个 debug 包（约 94MB）。核心变更：**M5.5 聊天图片——多模态附件发送 + 图片块渲染 + 文件图片缩略图/浮窗预览**。

**新特性：**

- ✨ **聊天发图（多模态）**：图片附件经 `session/attachImages` 规范化入库 → durable ref → `session/prompt` 的 `image` 块发送，让支持视觉的模型真正「看到」图片；当前模型不支持图片或上传失败时自动回退 Markdown 文本发送，不丢消息
- 🖼️ **Markdown 图片块渲染**：独立成段的 `![alt](src)` 用 Coil 圆角线框渲染（`appconfig/markdown-config.js` 可配圆角 / 线框 / 最大高度 / 加载占位 / 错误文案），支持本地绝对路径、DSH 工作区相对路径、`file://` 与网络 URL，点击进全屏预览
- 📁 **文件管理图片预览**：图片列表显示 Coil 缩略图（失败回退图标）；点击图片弹出全屏浮窗预览，支持单指拖动、双指缩放（1x~6x）、双指旋转与 90° 旋转按钮
- 🤖 **模型多模态标识**：`llm.listModels` 透传 `inputModalities`，模型卡显示「多模态」角标；编辑模型对话框可开关图片输入，聊天页发送图片前按模型能力自动裁决
- 🖼️ **输入框附件缩略图**：待发送的图片附件在输入框上方显示缩略图，非图片附件保持引用图标
- 🧹 **空白段过滤**：流式解析 / 落库 / 气泡渲染三处过滤 DSH 工具调用前常发的空 `text` delta（如 `"\n\n"`），避免思考与工具调用之间出现空灰框

**本包修复：**

- 🔧 `ChatSegmentJson` 不再为纯空白 delta 新建 Reasoning / Text 空段
- 🔧 聊天附件逻辑统一收敛到 `ChatAttachments.kt`（引用 id / Markdown 链接 / 发送文本构造），图片渲染走 `![文件名](路径)`

**⚠️ 升级须知：**

- 本次仅升级 App 代码，runtime.bin 未变（`attachImages` / `imageLimits` RPC 在 0.2.2 基线已随 runtime 就绪）
- 图片发送依赖模型侧多模态能力：模型管理里请确认目标模型带「多模态」角标（如 `deepseek-v4-flash-vision-exp`），没有则自动走文本回退
- 真机验收建议：聊天发图（含压缩超限大图）✓ 图片块点击全屏预览（缩放/旋转）✓ 文件管理缩略图与图片浮窗 ✓ 不支持图片的模型回退 ✓

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.2...v0.2.3
