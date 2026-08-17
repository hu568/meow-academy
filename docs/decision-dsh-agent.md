# 🤖 方案决策：内置 Pi Agent 换成 DeepSeek Harness（DSH）

> 决策状态：✅ 已采纳（主人呜咕拍板，2026-08-13）
> 背景：DeepSeek Harness（`dsh`）发布（0.1.0-rc.5，developer preview），主人决定把安卓 App 内置的 pi agent 换成 DSH
> 相关文档：[PLAN.md](../PLAN.md) · [decision-local-pi-agent.md](decision-local-pi-agent.md) · [plan-phase1.md](plan-phase1.md)
> 2026-08-15 补充：DSH 已 fork 独立副本到仓库根 `dsh/`（gitignore，源码 92MB），`build-dsh-closure.sh` 默认从这里打闭包，与 `D:\deepseek-harness`（本机正在运行的 DSH harness）彻底解耦；改沙箱/插件都在 fork 里改，勿动 `D:\deepseek-harness`。
> 2026-08-15 补充二（主人指示）：**真终端提前到 M2** —— meow-academy 内置持久 PTY bash，DSH 跑在真终端里（bash 子进程），聊天 JSON-RPC 改走本地 socket；同时**弃用并删除 pi-agent-backend/**（Fastify 云端后端），RAG 算法归档到 docs/reference/rag-algorithm.md。

---

## 一、决策点

| 决策点 | 结论 |
|---|---|
| 替换目标 | **只换安卓 App 内置的 Pi Agent 运行时**（`pi --mode rpc`）；`pi-agent-backend/` 云端开发后端不动 |
| DSH 嵌入形态 | **jsonrpc 协议**（`@deepseek-ai/dsh-sdk-jsonrpc-server`，JSON-RPC 2.0 over stdio），不用 ACP / headless CLI |
| 聊天流式 | 保留逐 token 打字机效果：`session.event` 里的 `assistant/chunk`（`text-delta` / `reasoning-delta`），等价 pi 的 `text_delta` / `thinking_delta` |
| 停止生成 | 自定义插件 `session/cancel` → `agent.cancel()`（DSH 原生 API；jsonrpc 协议本身无 cancel 方法） |
| 终端 bash | 自定义插件 `session/bash`（复用 DSH shell 执行器；jsonrpc 协议本身无 bash 方法） |
| 会话 | JSONL 会话持久化（`DSH_SESSION_ROOT`），`compression: none` 避开 zstd 原生模块 |
| 体积 | 接受砍功能精简；**实测精简后 77.5MB 解压 / gzip 18.7MB，APK 预计 ~110MB**（见 §三），200MB 红线余量充足 |
| 插件化 | ✅ **精简不影响插件化**：Cordis Loader/include/group 核心保留，可通过增删 cordis.yml 条目 + 依赖来加减插件（见 §四） |

### 为什么选 jsonrpc 协议而不是 ACP

| 能力 | pi RPC（现状） | DSH jsonrpc | DSH ACP |
|---|---|---|---|
| 逐 token 流式 | ✅ text_delta | ✅ `assistant/chunk` | ❌ 整段提交 |
| 工具调用卡片 | ✅ | ✅ `tool/call` / `tool/result` | ❌ 不上线 |
| 停止生成 | ✅ abort | ⚠️ 无原生方法 → 自定义 `session/cancel` | ✅ session/cancel |
| 会话恢复 | — | ✅ JSONL/SQLite 持久化 | ❌ fresh only |
| 终端命令 | ✅ bash | ✅ 真终端 PTY（meow-academy 内置；DSH 跑在真终端里，聊天走 socket） | ❌ 走 agent 不可靠 |

jsonrpc 协议的事件流（`session.event` + `session.status`）与 pi 的 message_update 模型对齐，体验可完整复刻；缺失的 cancel/bash 用**自定义 Cordis 插件**补齐（约 40-60 行 TS，不打 fork）。

---

## 二、目标架构（替换后）

```
安卓 App（Kotlin/Compose）
  ├─ 聊天页 ─┐
  ├─ 终端页 ─┤── RuntimeManager → DshRpcClient（JSON-RPC 2.0, newline-delimited）
  └─ 设置页 ─┘            │ stdin/stdout 管道（复用 JsonlFrameReader）
  ┌───────────────────────▼──────────────────────┐
  │ 内置 Termux runtime（meow-runtime/，打包逻辑复用）│
  │  ├─ bin/node（Termux node ≥22.19，真机 26.4 ✓）│
  │  ├─ lib/…  DSH deploy closure（精简后 ~15MB 解压）│
  │  ├─ dsh/cordis.yml          喵学堂组合（插件清单）  │
  │  ├─ dsh/meow-extensions/    自定义插件（相对路径）  │
  │  │   ├─ cancel（session/cancel → agent.cancel）│
  │  │   └─ bash（session/bash → shell 执行）      │
  │  └─ 入口：node …/dsh-sdk-jsonrpc-demo/packaged-bin.js dsh/cordis.yml
  └───────────────────────┬──────────────────────┘
                          │ HTTPS（DeepSeek API）
                          ▼
                 DeepSeek API（deepseek-v4-flash）
```

**喵学堂 cordis.yml 组合**（参考 `examples/jsonrpc-agent/cordis.yml` + `minimal.cordis.yml` 精简）：

- `dsh-sdk-jsonrpc-server`（协议服务器）＋ `meow-extensions`（自定义 cancel/bash）
- `dsh-llm-deepseek`（`DEEPSEEK_API_KEY` / 可选 `DEEPSEEK_BASE_URL`；模型 deepseek-v4-flash）
- `dsh-agent-spine-demo`（persona：喵喵老师学习助教）
- 工具：`dsh-tool-bash`（终端用）、`dsh-fs-local` + `dsh-tool-fs`（M3 文件管理铺路）、`dsh-tool-str-replace-editor`、`dsh-tool-todo`
- `dsh-session-persistence-jsonl`（`compression: none`）、`dsh-compaction-basic`、`dsh-token-meter`
- **sandbox：`danger-full-access`**（Android 无 landlock/bwrap；与 pi 时代等价，App 沙箱本身隔离）
- **不打包**：dsh-web 全家桶、web-search(-exa/-perplexity/-deepseek)、web-fetch、ACP、subagent（及 control/fork）、hooks、session-query、plan-mode、goal、workflow、schedule、lsp、user-approval UI、`dsh-llm-pi-ai` 及其 SDK 链、persistent bash（node-pty 原生模块）

---

## 三、体积实测结论（PC 实测，2026-08-13）

用官方 jsonrpc closure 清单（`python/sdk-runtime/package.json`，即 DSH 为 Python SDK 打包的最小运行集）在 PC 上 `pnpm deploy --prod --legacy` 实测：

| 组合 | 解压 | gzip 后（runtime.bin 同款单流压缩） |
|---|---|---|
| 官方完整 closure（含 web/搜索/subagent/持久终端/pi-ai 全链） | 165.8 MB | — |
| **喵学堂清单实测（2026-08-14 复测，deploy/meow-runtime）**：去 web、搜索、ACP、subagent 驱动、hooks、plan/goal 命令、workflow、持久终端（node-pty）、**sandbox 原生模块（landlock）**、sqlite/query、lsp/mcp、pi-ai SDK 链 | **78.6 MB** | **19.1 MB**（node_modules+dsh 单流 gzip；修复逃逸链接后、node-prune 前） |

**大头明细**（砍掉的部分）：

- `node-pty` 62.6MB —— 持久终端原生模块（真终端提前到 M2 后需重新纳入；Android 上用 fork @mmmbuto/node-pty-android-arm64 或 Kotlin 侧 bionic forkpty）
- `@google/genai` 13.7MB + `@mistralai` 9.2MB + `openai` 7.2MB + `@anthropic-ai/sdk` 3.9MB + aws-sdk + otel 11.5MB ≈ 58MB —— pi-ai 各家 LLM SDK 链（本方案用 `dsh-llm-deepseek` 直连官方 API，全不需要）

**预计 runtime.bin：~35-40MB**（closure 18.7 + node ~15 + 证书等），**APK 预计 ~90-110MB**——现实测（86MB）已低于预计，200MB 红线余量超过一倍。

---

## 四、插件化机制（精简后依然可增删插件）

✅ **精简不影响插件化**：精简只是「closure 里少装包 + cordis.yml 里少列条目」，Cordis 核心（context/registry/service/fiber/事件）+ Loader（按名加载插件）+ include 组合 + 内置 `cordis:include` / `cordis:group` 全部保留。官方 `examples/jsonrpc-agent/cordis.yml`（约 15 条）与 `minimal.cordis.yml`（约 12 条）就是「精简组合完整插件化运行」的现成范例；closure 装了 227 个包但运行时只加载配置列出的条目——「装多少」与「用多少」解耦。

**增删插件 = 改 cordis.yml + 依赖，不动核心**：

| 操作 | 做法 | 示例 |
|---|---|---|
| 加官方插件 | closure 里装该包（npm/依赖清单）+ cordis.yml 加一行 `- id / name: @deepseek-ai/dsh-xxx` | 以后加 web 搜索：装 `dsh-web-search-deepseek` + 配置一行 |
| 删插件 | cordis.yml 移除条目（可再移出依赖清单省体积） | 砍掉不需要的工具 |
| 自写插件 | 写 Cordis 插件（`apply(ctx, config)`，TS/JS），cordis.yml 用**相对路径**引用（放 `dsh/meow-extensions/`）；或打成 npm 包 | 本计划的 `session/cancel`、`session/bash`；以后 RAG 工具、自定义学习工具都走这条路 |
| 补丁层 | 可选 `cordis.patch.yml`：不改主配置即可覆盖条目 config / `insert` 新条目（boot 的 patches 参数支持） | 真机调试时临时开关插件 |

**插件解析规则**（`dsh-app-boot` 文档确认）：bare 包名（`@deepseek-ai/dsh-*`）从 closure 安装树解析（入口 `packaged-bin.js` 传 `bareModuleBaseUrl`）；相对路径相对 config 目录解析。

**未来扩展路径**（都只需增插件，不改核心）：M4 RAG 工具（自定义 `rag_search` 工具插件，等价 pi 时代 `agent.ts` 的做法）、web 搜索/抓取（官方插件）、MCP 客户端（DSH 有 `dsh-mcp-client`）、自定义学习工具。

---

## 五、实施步骤

### 阶段 0：PC PoC（✅ 全部完成，2026-08-14）

1. ✅ **体积实测**：见 §三。喵学堂清单 `deploy/meow-runtime` 复测 **78.6MB 解压 / 19.1MB gzip**。
2. ✅ **npm 发布确认**：包已发布但版本混杂（jsonrpc-server 0.0.1-rc.5 / app-boot 0.1.0-rc.6 / llm-deepseek 0.0.1-rc.1 …），不可靠 → **采用 checkout `pnpm deploy` 路径**（验证可行，且新发现必须 `--config.link-workspace-packages=false` 让 workspace 包按 files 字段真实拷贝）。
3. ✅ **协议验证**（`.tmp/dsh-poc/client.mjs`，全绿）：initialize / session/prompt 逐 token 流式（text-delta+reasoning-delta）/ turn/end reason / session.status idle / 工具调用卡片（tool/call+tool/result）/ shutdown。
4. ✅ **插件验证**：meow-extensions（meow-jsonrpc 插件）实现并验证 `session/cancel`（turn/end reason=aborted ✓）、`session/bash`（流式增量 session.bashOutput + exitCode ✓）、`session/bashCancel` ✓、`ping` ✓；「增删插件」流程验证通过（加 hello 插件条目 → 重启出现标记；删条目 → 标记消失）。
5. ✅ **会话恢复（超出原计划的实测）**：同 sessionId 跨进程重连时官方 server 只会 fresh create → 撞 persistence id-collision 守卫。**插件化解决**：meow-jsonrpc 覆盖会话获取，磁盘有日志时走 `ctx.agents.resume()`（加载历史 + 崩溃修复 + 续接回合），实测进程 #2 全新拉起后正确记得进程 #1 的对话内容。
6. ✅ **可移植性坑**：pnpm deploy 在 Windows 上会产出指向 checkout 的绝对 symlink（`link:vendor/*` 覆写 + 未物化依赖），Termux 必断。`tools/fix-closure-links.mjs` 一键归一化：store 路径重映射闭包内、vendor 包物化到 `.meow-vendor/`（0.32MB）、死链删除，产物 tar.gz 零逃逸链接。

### 阶段 1：runtime 重打包（真机 Termux）

1. 改造 `android-app/runtime-assets/build-runtime.sh`：node 二进制 + 动态库 + CA 束拷贝逻辑**原样保留**；新增 DSH closure（npm 安装或拷贝的 `node_modules/@deepseek-ai/…` + `dsh/cordis.yml` + `dsh/meow-extensions/`）拷贝；**node-prune 裁剪 + 排除 node-pty 目录**；仍输出 gzip 流 `runtime.bin`（.bin 后缀避开 AGP）。
2. 真机 Termux 手动验证：直接跑 `node …/packaged-bin.js cordis.yml`，echo JSON-RPC 帧进 stdin 确认能通（复用现有 adb/ssh 流程）。

### 阶段 2：Kotlin 协议层（`com.meow.academy.rpc` + `runtime`）

1. `RpcModels.kt` → 重写为 `DshProtocol.kt`：
   - JSON-RPC 2.0 帧模型（`{jsonrpc,id,method,params}` / `{jsonrpc,id,result|error}` / 通知 `{jsonrpc,method,params}`）
   - 请求：`initialize{cwd,provider,model}`、`session/prompt{sessionId,contentBlocks}`、`session/cancel{sessionId}`、`session/bash{sessionId,command}`、`shutdown`
   - 通知：`session.event{sessionId,event}`、`session.status{sessionId,status}`
   - 事件模型：`assistant/chunk`（chunk.type: text-delta/reasoning-delta/tool-call-delta/block-end/finish）、`tool/call`、`tool/result`、`turn/start|end`（reason: completed/max-tokens/error）、`user/message`
2. `PiRpcClient.kt` → `DshRpcClient.kt`：复用 `JsonlFrameReader`；JSON-RPC id 路由 response、按 sessionId 过滤广播 `session.event`；`connect()` 时先 `initialize`（cwd=filesDir、provider=deepseek-official、model 来自设置）。
3. `PiProcessLauncher.kt` → `DshProcessLauncher.kt`：入口改 `packaged-bin.js` + cordis.yml 路径；环境变量：`DEEPSEEK_API_KEY`（保留）、`DSH_CWD`=filesDir、`DSH_SESSION_ROOT`=filesDir/.dsh-sessions、可选 `DEEPSEEK_BASE_URL`；**保留** linker64/LD_LIBRARY_PATH/OPENSSL_CONF/NODE_EXTRA_CA_CERTS/NODE_OPTIONS(dns-shim)/PATH。
4. `RuntimeManager` / `PiRuntimeService` / `PiKeepAliveWorker` / `RuntimeExtractor` / `AppLifecycleObserver`：逻辑不动，仅类名/引用更新（Pi→Dsh 命名）。

### 阶段 3：ViewModel 适配

1. `ChatViewModel.runStream`：改消费 `session.event`（按 sessionId 过滤）——`assistant/chunk`→content/thinking 追加（节流落库逻辑保留）、`tool/call`→工具卡片、`tool/result`→结果；活动区间 = prompt 入队 → 该 session `session.status=idle`（兜底超时）；连接断开取消逻辑保留。`stopGenerating` → `session/cancel`。
2. `TerminalViewModel`：`runCommand` / `runCd` / `abortRunning` 改为 `session/bash`（自定义方法，返回 output/exitCode）；虚拟 cwd 逻辑保留。
3. Room 会话：sessionId 映射（Room 长 id → DSH sessionId 字符串）；聊天历史仍由 Room 管理，DSH 侧 JSONL 负责模型上下文。

### 阶段 4：构建与验收

1. `assembleDebug` → APK ≤ 200MB（预计 ~110MB）；`adb install -r` 真机。
2. 真机验收：聊天流式/思考/工具卡片/停止/重试、终端 bash、三档常驻、杀进程后重启恢复、断网兜底、错误提示。
3. 文档更新：`PLAN.md`（里程碑登记）、`AGENTS.md`（架构/命令/runtime 打包说明）。

---

## 六、文件级改动清单

| 文件 | 动作 | 状态 |
|---|---|---|
| `android-app/runtime-assets/build-runtime.sh` | 改：node+bash 二进制、DSH 闭包解包、CA/DNS shim 保留 | ✅ |
| `android-app/runtime-assets/build-dsh-closure.sh` | 新增：PC 侧一键生成闭包（deploy → 链接归一化 → node-prune → tar.gz） | ✅ |
| `android-app/runtime-assets/tools/fix-closure-links.mjs` | 新增：闭包链接归一化（逃逸链接 → 闭包内相对链接） | ✅ |
| `android-app/runtime-assets/dsh/cordis.yml` | 新增：喵学堂组合（插件清单） | ✅ |
| `android-app/runtime-assets/dsh/meow-extensions/meow-jsonrpc.js` | 新增：cancel/bash/bashCancel/ping/resume 插件（相对路径引用） | ✅ |
| `android-app/app/src/main/assets/runtime.bin` | 重新生成（gitignore；真机 Termux 步骤） | ⏳ 待真机 |
| `rpc/RpcModels.kt` | 重写 → `rpc/DshProtocol.kt`（旧文件删除） | ✅ |
| `rpc/PiRpcClient.kt` | 重写 → `rpc/DshRpcClient.kt`（旧文件删除） | ✅ |
| `runtime/PiProcessLauncher.kt` | 重写 → `runtime/DshProcessLauncher.kt`（旧文件删除） | ✅ |
| `runtime/PiRuntimeService.kt` | 重写 → `runtime/DshRuntimeService.kt`（initialize 握手；旧文件删除） | ✅ |
| `runtime/PiKeepAliveWorker.kt` | 重写 → `runtime/DshKeepAliveWorker.kt`（ping 心跳；旧文件删除） | ✅ |
| `runtime/RuntimeManager.kt` / `AppLifecycleObserver.kt` / `RuntimeExtractor.kt` / `MeowAcademyApp.kt` / `AndroidManifest.xml` | 改：命名 + rpcClient 类型 + isInstalled 检查 | ✅ |
| `ui/chat/ChatViewModel.kt` | 改：session.event 事件流适配（assistant/chunk、tool/call、tool/result、turn/end） | ✅ |
| `ui/terminal/TerminalViewModel.kt` | 改：session/bash + workdir 参数 + session.bashOutput 增量 | ✅ |
| `PLAN.md` / `AGENTS.md` / `docs/decision-dsh-agent.md` | 改/新增 | ✅ |
| `pi-agent-backend/` | **不动**（云端开发后端保留） | ✅ |
| `D:\deepseek-harness`（DSH checkout） | 新增 `deploy/meow-runtime/` 清单 + `pnpm-workspace.yaml` 注册行 + 锁文件 importer（不 fork 源码） | ✅ |

---

## 七、风险与对策

| 风险 | 对策 |
|---|---|
| ~~DSH 依赖体积超红线~~ | ✅ 已实测排除：精简后 77.5MB 解压 / gzip 18.7MB，APK 预计 ~110MB（§三） |
| 所需包未发布 npm | 从 DSH checkout `pnpm deploy` 生成 closure（已实测可行）；锁定版本 0.1.0-rc.5 |
| node-pty 等原生模块 | 组合不加载 + 打包排除 node-pty 目录（省 62.6MB）；本阶段不用 persistent bash/PTY |
| sandbox 在 Android fail-closed | ✅ 决策升级：**不挂 sandbox 插件**（sandbox-local 静态 import landlock 原生模块，Android 必崩；官方 jsonrpc-agent 组合同样无 sandbox）；App 沙箱兜底 |
| ~~会话恢复不确定（jsonrpc 同 sessionId 重连）~~ | ✅ 已解决：官方 server 只 fresh create（撞 id-collision），meow-jsonrpc 插件改走 `agents.resume()`，实测跨进程恢复成功（§五 阶段0-5） |
| DSH developer preview API 变动 | 锁定版本；插件化隔离（增删插件不改核心，升级时只换 closure）；文档记录踩坑 |
| zstd 压缩原生依赖 | session-persistence-jsonl 配 `compression: none` |
| DNS/CA（旧坑） | dns-shim + OPENSSL_CONF/NODE_EXTRA_CA_CERTS 注入原样保留 |
| 插件化被精简破坏 | ✅ 机制独立于精简：Loader/include/group 是核心，`jsonrpc-agent`/`minimal` 组合即官方精简范例（§四） |
| ~~pnpm deploy 可移植性~~（Windows 绝对 symlink） | ✅ 已解决：`tools/fix-closure-links.mjs` 归一化（§五 阶段0-6）；deploy 必须带 `--config.link-workspace-packages=false` |

---

## 八、验收标准

1. 聊天：打字机流式、思考增量、工具卡片、停止生成生效、错误兜底（连接断开/模型报错提示清晰）
2. 终端：任意命令执行、cd 虚拟目录、输出/退出码正确、超时兜底
3. 常驻：三档开关行为与 M2 一致；进程被杀后自动重启
4. 会话：App 内会话列表/历史正常；重启后 DSH 侧上下文可恢复（或按降级方案）
5. 插件化：cordis.yml 增删一个插件条目并重启后生效（阶段 0 验证 + 真机抽查）
6. 体积：`release/meow-academy-<v>-debug.apk` ≤ 200MB（预计 ~110MB）
7. 文档：决策文档（含体积实测 + 插件化说明）+ AGENTS.md 更新完成

## 九、明确不做（本阶段边界）

- 弃用并删除 `pi-agent-backend/`（Fastify 云端后端，无客户端消费）；RAG 算法归档到 docs/reference/rag-algorithm.md；RAG/embedding 迁移到 DSH 仍留到 M4/M5
- 真终端（持久 PTY bash，termux 化）提前到 M2：DSH 跑在真终端里（bash 子进程），聊天改走本地 socket（stdio 不再承载协议）
- 不打包 DSH Web UI / subagent / web 搜索 / hooks / plan-mode 等（体积控制，但**扩展路径保留**——以后随时可加插件）
- 不 fork DSH 源码（仅写 meow-extensions 薄插件 + cordis.yml）

---

*—— 主人呜咕的专属猫娘助手 · 樱茈 🐾（2026-08-13）*