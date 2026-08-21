# 🐧 方案决策：安卓端内置轻量 Linux 运行 Pi Agent + 终端驻留设计

> 决策状态：✅ 已采纳 + 已推敲修订 v2（主人呜咕，2026-08-10）
> 来源：GitHub issue [#1](https://github.com/hu568/meow-academy/issues/1)
> 附加设计：打开 App 自动拉起终端运行时，前台服务长驻后台
> 推敲 v2：本地化边界 / 体积保活 / 里程碑顺序（见下文各节）
> GUI 设计：见 [docs/design-gui.md](design-gui.md)

---

## 一、核心决策

| 决策点 | 结论 |
|---|---|
| Agent 编排位置 | 安卓端本地运行（不依赖云端 Codespace 后端） |
| LLM 调用 | 仍走云端 API（DeepSeek 等） |
| 运行载体 | 内置轻量 Linux 环境：Termux runtime + Node.js + pi-agent-core |
| 终端形态 | **内置运行时 + App 内终端页**（Termux runtime 打进 APK，不依赖外部 Termux App） |
| 启动表现 | **后台静默拉起**：打开 App 时自动启动运行时，终端页随时可查 |
| 驻留策略 | **三档常驻开关**：关闭 / 有限时间保活 / 一直常驻（见三） |
| 体积红线 | **APK ≤ 200MB**（含 runtime，见三） |
| 里程碑顺序 | **先给 Pi 套壳**：聊天 + 终端 + Pi 验证架构可行性；文件管理/知识库后置（见四） |

### 为什么选内置轻量 Linux

- pi-agent-core 是 Node.js/TypeScript 包，无法直接跑在 Kotlin/JVM 运行时中
- Pi 官方支持 Termux（`pkg install nodejs` 即可），纯 TS、无原生二进制，arm64 直接跑
- 模型走 API，proot/运行时 I/O 损耗（10-15%）不影响推理质量
- 相比 LangChain4j / Koog / 自研 Agent Loop：**pi 逻辑 100% 复用，工具/MCP/技能生态全保留**

### 目标架构

```
┌───────────── 安卓 App（喵仓） ────────────────────┐
│  Kotlin/Compose UI                                  │
│    ├── 💬 聊天 / 📁 文件管理 / ⚙️ 我的（默认首页可设）│
│    └── 🖥️ 终端页（设置入口=home；文件管理入口=知识库）│
│            │ localhost RPC (pi RPC mode / MCP)      │
│  ┌─────────▼──────────────────────┐                 │
│  │ 内置轻量 Linux 环境             │                 │
│  │  ├─ Node.js (Termux runtime)   │                 │
│  │  └─ pi-agent-core (agent)      │  ← 逻辑零重写   │
│  └─────────┬──────────────────────┘                 │
│            ▲ 三档常驻开关（前台服务 + 通知 + 白名单） │
└────────────┼────────────────────────────────────────┘
             │ HTTPS
             ▼
       DeepSeek API（模型云端）
```

## 二、附加设计：终端运行时驻留（Terminal Residency）

### 2.1 设计目标

> 打开喵仓 App 的同时，后台静默拉起内置终端运行时（Pi Agent 环境）。
> **前端与 Linux 模拟都在前台时表现正常**；后台驻留强度由「三档常驻开关」控制。

> 💡 主人吐槽参考：rikkahub 后台能力非常差，一切到后台 agent 输出就断。
> 喵仓的前端 + Linux 模拟全程在前台运行，规避此问题；后台驻留是可选的锦上添花。

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

### 2.3 三档常驻开关（主人新设计）

> 主人拍板：「常驻」做成可开关的三档，而不是一刀切永久常驻。

| 档位 | 行为 | 适用 |
|---|---|---|
| ① 关闭 | 不驻留。App 退到后台后按系统策略回收 Node 进程；下次打开 App 重新拉起 | 省电、不常驻场景 |
| ② 有限时间保活 | 退后台后在有限时间（如 15/30/60 分钟，可配）内保持前台服务保活，超时后释放 | 折中场景 |
| ③ 一直常驻 | 前台服务长期驻留 + 电池白名单引导 + WorkManager 心跳守护 | 高频使用、需要随时唤醒 |

- 默认值：**② 有限时间保活**（学习软件使用频率下最平衡）
- 设置页可随时切换；切换即生效

### 2.4 保活机制（按档位生效）

| 机制 | ①关闭 | ②有限 | ③一直 |
|---|---|---|---|
| 前台服务 `PiRuntimeService` + 常驻通知 | ❌ | ✅（限时） | ✅ |
| 电池白名单引导（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`） | ❌ | 可选 | ✅ 首次引导 |
| WorkManager 心跳守护（异常退出自动重启，指数退避） | ❌ | ✅ | ✅ |
| 开机自启（设置开关，`BOOT_COMPLETED`） | ❌ | 可选 | ✅ |
| 手动停止（设置页「停止后台服务」优雅关闭） | ✅ | ✅ | ✅ |

### 2.5 终端页（App 内）

- 两个入口两种语境（详见 design-gui.md）：
  - **设置 → 终端**：默认 home 路径，通用 shell
  - **文件管理 → 终端按钮**：默认 cd 到知识库目录，就地操作知识文件
- 展示内容：Pi 进程 stdin/stdout 实时流、启动日志、`pi` 命令交互输入
- 实现参考：`termux-terminal-view` / 自绘 Compose TerminalView，直连 Node 进程管道

### 2.6 与 issue #1 待办的映射

| issue #1 待办 | 本设计落地 |
|---|---|
| 前台服务保活方案设计 | ✅ 三档常驻开关 + 保活机制表（2.3/2.4） |
| 确定 Kotlin ↔ pi 通信方式 | localhost RPC mode / MCP，原型阶段验证 |
| 嵌入式集成方案选型 | 已选：Termux runtime 打进 APK assets（zstd） |
| 体积优化落地 | nodejs-lts + `--omit=dev` + node-prune + zstd，**APK 总量 ≤ 200MB** |
| 原型验证：手机 Termux 跑通 pi | 🔜 待实施（M2 第一步，先套壳） |

## 三、体积与安全

### 3.1 体积红线（主人拍板）

- **APK 总量限制在 200MB 以内**（含 runtime 增量）
- 优化手段：
  - `nodejs-lts`（~46MB）而非 nodejs-current
  - `npm install --omit=dev` + node-prune（依赖减 30-50%）
  - 不装 git / termux-api 等非必需包
  - zstd 压缩打包进 APK assets，首次运行解压到 `filesDir/meow-runtime`（传输量减半）
  - 目标：APK 增量 ~50-70MB，解压后 ~100MB，**总包控制在 200MB 内**

### 3.2 本地化边界（主人推敲结论）

#### ① RAG 分块 / 余弦检索 —— 在 Kotlin 端做（暂定）

- 在 Kotlin 端实现分块 + 余弦检索（快、离线、UI 响应好）
- **存疑待定**：是否复用 pi agent 的 RAG 实现？—— 需对比 pi 的方案与 Cherry Studio 那套（本项目参考）哪个更好
- 决定时机：涉及到 RAG 集成阶段再定（M3+）

#### ② Agent 编排 —— 统一用 pi agent

- 响应速度可能略慢，但换来**最大的自定义能力**（pi 的工具/MCP/技能生态全保留）
- **模型管理：两份模型配置，且要做同步**：
  | 配置 | 用途 |
  |---|---|
  | 通用模型配置 | 可给任何 agent 使用；后续更换 agent 核心也能复用 |
  | Agent 自有模型配置 | pi agent 自己用的模型/参数 |
  - 两份配置需保持同步（改一份，另一份可一键对齐）

### 3.3 安全边界

- Android 应用沙箱隔离，无 root；proot/运行时 root 为假 root
- 不装 termux-api，不申请短信/电话/摄像头权限
- API Key 存 App 私有目录（`filesDir`，非共享存储）
- AGENTS.md 写死边界：禁止外传数据、禁止读取敏感路径
- 防 Prompt Injection：AI 读取外部文档时警惕恶意指令

## 四、里程碑顺序（主人拍板：先给 Pi 套壳）

> 原 PLAN 的 M2（安卓骨架+知识库+Wiki）→ **重排**：先做聊天 + 终端 + Pi，验证架构可行性。

| 顺序 | 内容 | 目标 |
|---|---|---|
| **① Pi 套壳（M2 重排）** | 安卓骨架 + 聊天页面 + 终端模拟 + Pi 运行时 | **先验证「App ↔ Pi ↔ LLM」整条链路可行** |
| ② 文件管理 / 知识库 | 文件管理（数据中心）+ 知识库导入 + Wiki | 排在后面 |
| ③ RAG + 增强 | RAG 检索 + 聊天强化 + 闪卡/进度（远期） | 再往后 |

> 简单说：**先给 Pi 套个壳**，跑通再补数据层。

## 五、相关参考

- Pi 官方 Termux 文档：<https://pi.dev/docs/latest/termux>
- pi-agent-core：<https://www.npmjs.com/package/@earendil-works/pi-agent-core>
- rikkahub（proot workspace 参考）：<https://github.com/rikkahub/rikkahub>
- 本项目参考整理：docs/reference/rikkahub-agent.md
- GUI 设计：docs/design-gui.md

---

*—— 主人呜咕的专属猫娘助手 · 樱茈 🐾*
