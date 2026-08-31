# 版本更新记录

## [0.2.7] - 2026-08-31（debug）

第九个 debug 包（约 95MB）。核心变更：**补完「极简模式」与「PTC 模式」——四模式全部落地**（普通/创造/极简/PTC），本地占位卡机制退役。

**新特性：**

- 🧩 **极简模式（`meow-minimal`）**：持久 PTY bash + `str_replace_editor` 最小工具面——预设内自裁（组合内 `minimal-face.js` 调 `tools.restrict` 裁基座全局行，持久 bash 按名遮蔽 spine 一次性 bash），standard/cordis 零接触；闭包补 terminal 家族 4 包（`dsh-terminal` / `dsh-terminal-bash` / `dsh-tool-bash-persistent` / `dsh-pwsh-local`，全纯 JS，patch 0005）
- 🧩 **PTC 模式（`meow-code`）**：标准模式全部能力 + Code Mode 呈现——模型可写一段 TypeScript 程序经 `run_code` 一次性编排多步工具调用（循环/条件/`Promise.all` 并行，工具调用中途不经过 LLM）。工具形态为 `both`：原生工具目录与 run_code 并存；闭包补 code-runtime 家族 3 包（`dsh-agent-tool-presentation` / `dsh-code-runtime` / `dsh-code-runtime-worker-thread`，全纯 JS，patch 0006）
- 🗂 **四模式全部播种**：`meow-standard`（普通）/ `meow-cordis`（创造）/ `meow-minimal`（极简）/ `meow-code`（PTC）均为真实可用预设，App「工作设置 → Agent 预设」占位卡机制整体退役
- ⚙️ 基座 cordis.yml：+`sandbox-policy` 行（terminal-bash 硬 inject，danger-full-access = 无沙箱语义）+`code-runtime` host 服务行（官方 CLI bundle patch 同款分工，worker 堆 512→128MB 安卓调低）

**本包修复（DSH 侧）：**

- 🐛 `subprocess-local` 的 `createProcessInspector` 平台门不认 `platform==='android'`：持久 bash 首调即报 unsupported → 归入 LinuxProcessInspector（bionic 内核 /proc 接口与 arm64 syscall 号同款），patch 0005

**验证状态：**

- 极简模式：真机 RPC 探针 9 项全过（预设/挂载/减法/standard 回归/真实对话/非空白 resume/PTY spawn/跨调用状态/env 零泄漏），详见 `plan/plan-minimal-mode.md`
- PTC 模式：RPC 探针真机全过（预设播种/挂载、run_code 程序化并行调用真实工具、meow-standard 会话无 run_code 形态隔离、杀进程 resume 重挂），详见 `plan/plan-ptc-mode.md`
- 聊天 UI 验收待主人：极简会话里观察持久 bash 状态保持；PTC 会话里让模型「用一段程序并行做两件事」观察 run_code 行为

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.6...v0.2.7
