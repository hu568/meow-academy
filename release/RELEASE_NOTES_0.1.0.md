# 版本更新记录

> `release/` 只存放安装包与本更新记录，其他文件（截图、调试脚本）一律放 `.tmp/`。

## [0.1.0] - 2026-08-11（debug，M2 里程碑完成）

首个可装机运行的 debug 包（105MB，含内置 Pi 运行时）。

**M2「给 Pi 套壳」全链路真机验收通过：**

- 内置 Termux 版 node + pi-coding-agent 运行时（runtime.bin 52.9MB），`linker64` 拉起 + RPC（stdin/stdout JSONL）通信
- 聊天页：流式输出（text_delta / thinking 事件）、会话持久化（Room）
- 终端页：`bash` 命令透传到 pi 进程执行
- 三档常驻策略（关闭 / 有限保活 / 一直常驻）+ 前台服务 + WorkManager 心跳
- 网络：INTERNET 权限修复（此前缺权限导致沙箱内 socket 全 EPERM）
- 主题：浅色/深色/跟随系统，Material You 动态取色

**里程碑进度：**

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 后端网关（Fastify + pi-ai + RAG） | ✅ 已完成（未发布独立版本） |
| M2 | 安卓端套壳（聊天/终端/设置/常驻） | ✅ 0.1.0 |
| M3 | 文件管理 + md 知识库 + SQLite Wiki | ⏳ 规划中 |

安装：`adb install -r release/meow-academy-0.1.0-debug.apk`
