# 🐧 方案决策：安卓端内置轻量 Linux 运行 Pi Agent + 终端驻留设计

> 决策状态：✅ 已采纳（主人呜咕拍板，2026-08-10）
> 来源：GitHub issue [#1](https://github.com/hu568/meow-academy/issues/1)
> 附加设计：打开 App 自动拉起终端运行时，前台服务长驻后台（2026-08-10 补充）

---

## 一、核心决策

| 决策点 | 结论 |
|---|---|
| Agent 编排位置 | 安卓端本地运行（不依赖云端 Codespace 后端） |
| LLM 调用 | 仍走云端 API（DeepSeek 等） |
| 运行载体 | 内置轻量 Linux 环境：Termux runtime + Node.js + pi-agent-core |
| 终端形态 | **内置运行时 + App 内终端页**（Termux runtime 打进 APK，不依赖外部 Termux App） |
| 启动表现 | **后台静默拉起**：打开 App 时自动启动运行时，终端页在导航内随时可查 |
| 驻留策略 | 前台服务（Foreground Service）持有 Node 进程，长时间驻留后台 |

### 为什么选内置轻量 Linux

- pi-agent-core 是 Node.js/TypeScript 包，无法直接跑在 Kotlin/JVM 运行时中
- Pi 官方支持 Termux（`pkg install nodejs` 即可），纯 TS、无原生二进制，arm64 直接跑
- 模型走 API，proot/运行时 I/O 损耗（10-15%）不影响推理质量
- 相比 LangChain4j / Koog / 自研 Agent Loop：**pi 逻辑 100% 复用，工具/MCP/技能生态全保留**

### 目标架构

```
┌───────────── 安卓 App（喵学堂） ────────────────────┐
│  Kotlin/Compose UI                                  │
│    ├── 学习/聊天/Wiki 界面                           │
│    └── 🖥️ 终端页（查看 Pi 输出 / 手动输入命令）       │
│            │ localhost RPC (pi RPC mode / MCP)      │
│  ┌─────────▼──────────────────────┐                 │
│  │ 内置轻量 Linux 环境             │                 │
│  │  ├─ Node.js (Termux runtime)   │                 │
│  │  └─ pi-agent-core (agent)      │  ← 逻辑零重写   │
│  └─────────┬──────────────────────┘                 │
│            ▲ 前台服务保活（常驻通知 + 电池白名单）     │
└────────────┼────────────────────────────────────────┘
             │ HTTPS
             ▼
       DeepSeek API（模型云端）
```

## 二、附加设计：终端运行时驻留（Terminal Residency）

### 2.1 设计目标

> 打开喵学堂 App 的同时，后台静默拉起内置终端运行时（Pi Agent 环境），
> 并让它**长时间驻留后台**——下次打开 App 时 Pi 仍在运行，随时可用。

### 2.2 启动链路（App 冷启动）

```
Application.onCreate / MainActivity.onCreate
  └─ 检查运行时状态（RuntimeManager）
       ├─ 已运行  → 跳过（心跳确认 Node 存活）
       └─ 未运行  → 首次运行：解压 assets/runtime.zst → app 私有目录
                    → 启动前台服务 PiRuntimeService
                    → 拉起 node pi RPC server（stdin/stdout 交给终端页）
                    → 连接成功 → App 进入主界面（全程静默，无弹窗）
```

### 2.3 驻留与保活（解决 issue #1 风险：「进程杀手会杀 Node」）

| 机制 | 说明 |
|---|---|
| 前台服务 | `PiRuntimeService` 持有 Node 进程，`startForeground()` + 常驻通知（低优先级通知渠道） |
| 电池白名单 | 首次启动引导用户加入「忽略电池优化」（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`） |
| 进程守护 | WorkManager 周期心跳：Node 进程异常退出后自动重启（指数退避） |
| 开机自启 | 可选（设置开关）：`BOOT_COMPLETED` 广播恢复驻留 |
| 手动停止 | 设置页「停止后台服务」→ 优雅关闭 Node + 撤销前台服务；下次打开 App 再自动拉起 |

### 2.4 终端页（App 内）

- 导航项「终端」，默认不抢占首屏（符合「后台静默拉起」）
- 展示内容：Pi 进程 stdin/stdout 实时流、启动日志、`pi` 命令交互输入
- 实现参考：`termux-terminal-view` / 自绘 Compose TerminalView，直连 Node 进程管道
- 价值：排障（看启动日志）、高级用户手动操作 Pi Agent、演示

### 2.5 与 issue #1 待办的映射

| issue #1 待办 | 本设计落地 |
|---|---|
| 前台服务保活方案设计 | ✅ 见 2.3（PiRuntimeService + 白名单 + 心跳） |
| 确定 Kotlin ↔ pi 通信方式 | localhost RPC mode / MCP，原型阶段验证 |
| 嵌入式集成方案选型 | 已选：Termux runtime 打进 APK assets（zstd） |
| 体积优化落地 | nodejs-lts + `--omit=dev` + node-prune + zstd，目标 APK 增量 ~50-70MB |
| 原型验证：手机 Termux 跑通 pi | 🔜 待实施（M3.5 第一步） |

## 三、体积与安全（沿用 issue #1）

### 体积优化清单

- `nodejs-lts`（~46MB）而非 nodejs-current
- `npm install --omit=dev` + node-prune（依赖减 30-50%）
- 不装 git / termux-api 等非必需包
- zstd 压缩打包进 APK assets，首次运行解压到 `filesDir/meow-runtime`（传输量减半）
- 目标：APK 增量 ~50-70MB，解压后 ~100MB

### 安全边界

- Android 应用沙箱隔离，无 root；proot/运行时 root 为假 root
- 不装 termux-api，不申请短信/电话/摄像头权限
- API Key 存 App 私有目录（`filesDir`，非共享存储）
- AGENTS.md 写死边界：禁止外传数据、禁止读取敏感路径
- 防 Prompt Injection：AI 读取外部文档时警惕恶意指令

## 四、风险

- APK 体积增加 ~50-70MB（可接受，换取 Agent 本地化）
- 前台服务在部分厂商 ROM（小米/华为）需额外引导：自启动权限、电池白名单
- Termux runtime 版权：GPL-3.0（Termux）/ GPL-2.0+（proot）——学习/开源项目可用，闭源上架需规避
- 首次启动解压较慢（~100MB 写入），需进度提示

## 五、相关参考

- Pi 官方 Termux 文档：<https://pi.dev/docs/latest/termux>
- pi-agent-core：<https://www.npmjs.com/package/@earendil-works/pi-agent-core>
- rikkahub（proot workspace 参考）：<https://github.com/rikkahub/rikkahub>
- 本项目参考整理：docs/reference/rikkahub-agent.md

---

*—— 主人呜咕的专属猫娘助手 · 樱茈 🐾*
