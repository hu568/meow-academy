# 版本更新记录

## [0.2.5] - 2026-08-28（debug）

第七个 debug 包（约 94MB）。核心变更：**主题颜色/聊天背景/组件级色槽接入动态配置架构 + 文件编辑器稳定性三连修复（行号/光标跟随/超长行）**。

**新特性：**

- 🎨 **主题接入动态配置架构**：主题颜色、聊天背景、组件级色槽统一走 `theme-config.jsonc` 动态配置（默认模板 / 用户覆盖 + 深合并），与 Markdown 配置同一套热更机制；设置页主题/聊天背景对话框重构
- 📚 **动态配置设计规范更新**：`docs/design-dynamic-config.md` 收敛为统一规范，主题与 Markdown 配置共用同一架构

**本包修复：**

- 🐛 **文件编辑行号显示修正**：真实逻辑行编号 + 空行/软换行正确 + 换行模式下行号对齐
- 🐛 **切回编辑模式光标不跟随**：恢复流程释放抑制后 `followTick` 触发光标跟随补跑
- 🐛 **不换行模式超长行闪退**：绕开 `OutlinedTextFieldDefaults.DecorationBox` 超长单行渲染问题

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.4...v0.2.5