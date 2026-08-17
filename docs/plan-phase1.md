# 🐾 第一阶段（M2）细化规划：给 Pi 套壳

> 状态：✅ M2 主体完成（2026-08-11，真机全链路验收通过；余 PLAN.md 数据回填）
> 目标里程碑：M2「给 Pi 套壳」——安卓骨架 + 聊天 + 终端 + Pi 本地运行，验证整条链路可行
> 依据：docs/decision-local-pi-agent.md（推敲 v2）、docs/design-gui.md（信息架构 v1）

---

## 一、目标与验收标准

> 简单说：**先给 Pi 套个壳**，跑通「App ↔ Pi ↔ DeepSeek」整条链路，再补数据层。

### M2 完成态（验收标准）

1. ✅ 安卓 App 可安装运行，底部导航三板块（💬聊天 / 📁文件管理 / ⚙️我的）可切换
2. ✅ 打开 App 后台静默拉起 Pi 运行时（`pi --mode rpc`），全程无弹窗
3. ✅ 聊天页可对话：流式出字、Markdown 渲染、可停止生成
4. ✅ 终端页可交互：两个入口（设置=home / 文件管理=知识库），能跑命令
5. ✅ 三档常驻开关可用（关闭 / 有限保活 / 一直常驻）
6. ✅ APK 体积 ≤ 200MB（本阶段先测 runtime 部分）
7. ✅ 浅色/深色/主题切换可用

### 明确不做（本阶段）

- ❌ 文件管理（数据中心）→ M3
- ❌ 知识库导入 / SQLite Wiki / RAG → M3/M4
- ❌ 双模型配置同步 UI → M4（本阶段仅存雏形）

## 二、环境准备（本机实测）

> ✅ 2026-08-10 M2.0/M2.1 后全部就绪：SDK/Gradle/adb 本机已装，真机 Termux 已跑通。

| 依赖 | 状态 | 说明 |
|---|---|---|
| JDK 17 | ✅ 已有（Temurin 17.0.20） | Gradle 构建需要 |
| Android SDK | ✅ 已装（cmdline-tools + platform-tools + platforms;android-34 + build-tools;34.0.0） | 路径 `C:/Users/Administrator/AppData/Local/Android/Sdk` |
| Gradle | ✅ 已装（wrapper 8.9 随仓库提交） | `./gradlew assembleDebug` 构建通过 |
| adb | ✅ 已装（37.0.1） | 真机调试可用 |
| 真机 | ✅ 已有（Termux 就绪） | node 26.4 + pi 0.84.1 + API key 已配，SSH 192.168.0.173:8022 |

> 📌 本机构建安卓工程前，先装 Android SDK（cmdline-tools + platform-tools + platforms;android-34 + build-tools），或用 Android Studio 一把装齐。

## 三、关键技术决策（本阶段锁定）

### 3.1 Kotlin ↔ Pi 通信：RPC mode（选定）

```
┌─ 安卓 App ──────────────────────────┐
│  Kotlin (Compose UI)                 │
│    │ JSONL over stdin/stdout         │
│    ▼                                 │
│  Pi 进程: pi --mode rpc [options]    │
└──────────────────────────────────────┘
```

- **协议**：`pi --mode rpc`，命令走 stdin、事件走 stdout，严格 JSONL（LF 分割）
- **命令**：`prompt` / `abort` / `set_model` / `bash` / `session` 等（带可选 `id` 关联）
- **事件**：`agent_start/end`、`message_update`（含 `text_delta` 流式增量）、`tool_execution_*`、`extension_ui_request`（对话框类，需要 App 弹窗响应）
- **优势**：Pi 官方为嵌入场景设计，无需起 HTTP 服务、零端口占用、进程生命周期完全由 App 掌控
- **参考**：<https://pi.dev/docs/latest/rpc>（含 Python/Node 示例客户端）

### 3.2 终端页与聊天页共用一个 Pi 进程

- **聊天** → RPC `prompt` 命令
- **终端** → RPC `bash` 命令（RPC mode 原生支持 bash 执行，返回 stdout/exitCode）
- 不需要真的接 PTY！终端页 = 发 `bash` 命令 + 渲染返回输出，**大幅简化实现**
- 文件管理入口的「cd 到知识库」→ 通过 RPC 维护会话工作目录实现

### 3.3 运行时打包：nodejs-lts + pi-coding-agent（zstd 压缩进 assets）

- 复用 issue #1 体积清单：`nodejs-lts`（~46MB）+ `npm i -g --ignore-scripts @earendil-works/pi-coding-agent`
- 打包产物 zstd 压缩 → APK `assets/runtime.zst` → 首次运行解压到 `filesDir/meow-runtime`
- 只带运行必需内容：node 二进制 + pi-coding-agent + node_modules（不装 git / termux-api）
- 目标：runtime 解压后 ~100MB，APK 增量 ~50-70MB

### 3.4 会话持久化

- 聊天记录：Room（简单会话/消息表），M2 只做「存得下、读得出」
- Pi 自身 session 持久化：`--no-session`（M2 先不依赖 Pi 的 session 文件，App 自己管历史）

## 四、任务分解（M2.0 → M2.7）

### M2.0 原型验证：真机 Termux 跑通 pi RPC ⭐ ✅ 已完成（2026-08-10）

**验证环境**：真机 Android aarch64（Termux），SSH `192.168.0.173:8022`

**安装结果（实测）**：
```bash
pkg update && pkg upgrade        # ✅ 南科大镜像，20 包可升级
pkg install nodejs git           # ✅ node v26.4.0 / npm 11.19.0 / git 2.55.0
npm install -g --ignore-scripts @earendil-works/pi-coding-agent
                                 # ✅ pi 0.84.1（144 packages，20s）
mkdir -p ~/.pi/agent
```

**体积实测（未裁剪）**：
| 项 | 大小 |
|---|---|
| node 二进制 | 48M |
| pi-coding-agent 依赖 | **182M**（裁剪空间大） |
| Termux usr 总计 | 421M |

**协议验证（RPC mode）**：
- ✅ `prompt` 命令：`response` → `agent_start` → `turn_start` → `message_update`（`thinking_delta` / `text_delta` 流式）→ `turn_end`（含 usage/cost）→ `agent_end`
- ✅ `bash` 命令：`bash_execution_update`（流式增量，`id` 关联）→ `response`（`output`/`exitCode`/`cancelled`/`truncated`）
- ✅ 模型链路：`--provider deepseek --model deepseek-v4-flash`，DeepSeek API 流式回复「OK」
- ⚠️ **管道 EOF 后 pi 会退出**：bash/prompt 需要保持 stdin 打开（App 端天然满足——进程常驻）

**API Key 配置（实测）**：
- pi 读取环境变量 `DEEPSEEK_API_KEY`（`--api-key` 参数也可）
- **持久化位置**：`~/.profile`（Termux **login shell 不加载 `~/.bashrc`**，写 `.bashrc` 无效）✅ 已验证
- App 端（M2.2+）：RuntimeManager 拉起 pi 时通过环境变量注入 key，不依赖 Termux 配置文件

**交付物**：验证记录（完整事件流 ✅）+ 手机 Termux 已就绪（node+pi+key）
**验证**：RPC 客户端收到完整事件流 ✅ | bash 执行返回 ✅ | 聊天流式回复 ✅

### M2.1 安卓工程骨架 ✅ 已完成（2026-08-10）

> 交付物已达成：`android-app/` Compose 工程 + 三板块导航 + 主题系统 + 设置雏形。
> 验证：`./gradlew assembleDebug` 构建成功 ✅；真机安装打开 ⏳（待 M2.7 一并验收）。

**做什么**：
- 建 `android-app/` Gradle 工程（Kotlin + Compose + Material You，gradle wrapper 提交进仓库）
- 包名 `com.meow.academy`，minSdk 26 / targetSdk 34
- 底部导航三板块：💬聊天 / 📁文件管理（占位页）/ ⚙️我的
- 主题系统：浅色 / 深色 / 跟随系统 / 自定义（Material You 动态取色）
- 设置页雏形：默认首页选择、常驻三档开关（先存 DataStore）

**交付物**：可安装 APK，三板块切换 + 主题切换生效
**验证**：`./gradlew assembleDebug` 构建成功；真机安装打开

### M2.2 Pi 运行时集成（RuntimeManager）✅ 已完成（2026-08-11）

**做什么**：
- `runtime-assets/` 打包脚本：装 nodejs-lts + pi-coding-agent → 裁剪 → zstd 压缩 → `app/src/main/assets/runtime.zst`
- `RuntimeManager`（状态机）：未安装 → 解压中(进度) → 就绪 → 运行中 → 异常
- 首次运行解压（带进度提示，~100MB 写入）
- `PiRuntimeService`（前台服务）持有 Pi 进程，`startForeground()` + 低优先级通知

**交付物**：RuntimeManager + 打包脚本 + 前台服务
**验证**：真机首启解压完成，`pi --mode rpc` 进程拉起，stdin/stdout 管道连通

### M2.3 RPC 协议客户端（Kotlin）✅ 已完成（2026-08-11）

**做什么**：
- JSONL 帧解析器（按 `\n` 分割，容错 `\r\n`）
- 命令封装：`prompt` / `abort` / `bash` / `set_model`（带 id 关联）
- 事件模型：`agent_*` / `message_update`(text_delta/thinking) / `tool_execution_*` / `extension_ui_request`
- `extension_ui_request` 对话框子协议：`confirm` / `select` / `input` → 转发给 UI 弹窗 → 回 `extension_ui_response`

**交付物**：`PiRpcClient`（kotlinx.serialization）+ 事件流（Flow）
**验证**：连 M2.0 的 pi 进程，收完整事件流；Kotlin 单测跑 JSONL 解析

### M2.4 聊天页面 ✅ 已完成（2026-08-11，流式实测通过）

**做什么**：
- 会话列表 + 会话详情（Room 持久化）
- 输入框 + 发送 → RPC `prompt` → 流式渲染（thinking 折叠 + 正文增量）
- 停止生成按钮 → `abort`
- Markdown 渲染（标题/列表/表格/代码高亮/引用/LaTeX，参考 rikkahub）
- 工具调用可视化：`tool_execution_*` 事件展示为卡片（当前阶段：bash/rag 等可见即可）

**交付物**：可对话的聊天页
**验证**：真机发消息 → 流式出字 → 工具卡片 → 停止生效

### M2.5 终端页面 ✅ 已完成（2026-08-11）

**做什么**：
- 终端页 UI：命令输入 + 输出渲染（等宽字体、ANSI 色简易处理）
- 走 RPC `bash` 命令执行（非真 PTY，见 3.2）
- 双入口：设置→终端（home 路径）；文件管理→终端按钮（知识库目录，M3 前先落 home）
- Pi 启动日志可见（排障用）

**交付物**：可交互终端页
**验证**：终端里跑 `ls` / `pwd` / `echo` 正常返回输出与 exitCode

### M2.6 三档常驻开关 + 设置完善 ✅ 已完成（2026-08-11，三档实测通过）

**做什么**：
- 三档：①关闭 ②有限保活（15/30/60min 可配）③一直常驻
- 前台服务/白名单引导/WorkManager 心跳按档位生效（见 decision v2 §2.4）
- 手动停止按钮、开机自启开关（③档可选）
- 模型配置雏形：通用配置（provider/model）存 DataStore，Agent 自有配置暂存（同步 UI 到 M4）

**交付物**：完整设置页 + 常驻三档生效
**验证**：切档位 → 观察 Pi 进程生命周期符合预期（退后台后按档位保活/释放）

### M2.7 全链路验收 ✅ 主体完成（2026-08-11；余 PLAN.md 数据回填）

**做什么**：
- 完整走一遍：安装 → 首启解压 → 聊天（含工具）→ 终端 → 设置切换 → 杀进程恢复
- 测 APK 体积（runtime 增量），对照 ≤200MB 红线
- 补 PLAN.md / 决策文档的实测数据（体积、启动耗时、保活效果）

**交付物**：验收报告 + 里程碑 M2 标记完成
**验证**：全部验收标准 ✅

## 五、任务依赖关系

```
M2.0 原型验证（真机 Termux 跑 pi）
  └─→ M2.1 安卓骨架（可独立并行）
        ├─→ M2.2 运行时集成
        │     └─→ M2.3 RPC 客户端
        │           └─→ M2.4 聊天页
        │           └─→ M2.5 终端页
        └─→ M2.6 常驻开关（依赖 M2.2 的 PiRuntimeService）
              └─→ M2.7 全链路验收
```

## 六、风险与开放问题

| # | 风险/问题 | 对策 |
|---|---|---|
| 1 | ~~真机未定~~ ✅ 已解决（2026-08-10） | 真机 Termux（SSH 192.168.0.18:8022）已就绪，node+pi+key 配好 |
| 2 | Android SDK / Gradle 本机未装 | M2.1 已用 Gradle wrapper 构建通过；SDK 由 wrapper 自动下载 |
| 3 | pi 体积：实测依赖 182M（未裁剪） | M2.2 打包时用 `--omit=dev` + node-prune 裁剪，目标 ~100M |
| 4 | RPC `bash` 无真 PTY（vim 等交互程序不支持） | 本阶段接受「命令式终端」定位；真 PTY 留作远期增强 |
| 5 | 前台服务在国产 ROM 保活效果 | 三档开关已给用户选择权；M2.7 实测记录 |
| 6 | API Key 配置方式 | ✅ M2.0 已实测：`~/.profile` export `DEEPSEEK_API_KEY`；App 端由 RuntimeManager 注入 |

## 七、当前进度与下一步

**已完成**：
- ✅ M2.0 原型验证（真机 Termux：node 26.4 + pi 0.84.1 + RPC prompt/bash 全通 + key 持久化）
- ✅ M2.1 安卓骨架（android-app/ Compose 工程 + 三板块导航 + 主题 + 设置雏形）
- ✅ M2.2 Pi 运行时集成（RuntimeManager 状态机 + PiRuntimeService 前台服务 + RuntimeExtractor + PiProcessLauncher）
- ✅ M2.3 RPC 协议客户端（JSONL 帧解析 + 命令/事件模型 + response 按 id 路由 + 事件 SharedFlow）
- ✅ M2.4 聊天页（Room 会话/消息持久化 + 流式渲染 + 停止生成 + Markwon Markdown + 工具卡片）
- ✅ M2.5 终端页（RPC bash 命令 + 输出渲染 + 双入口 + systemBarsPadding 防遮挡）
- ✅ M2.6 三档常驻开关（AppLifecycleObserver 前后台策略 + PiKeepAliveWorker 心跳 + 模型管理/停止服务）

**真机验收实测（2026-08-10，新真机 192.168.0.18:8022 / u0_a169 / 0000，aarch64）**：
- ✅ App 安装运行不闪退（修掉 WorkManager 依赖 listenablefuture 被误排除的 NoClassDefFoundError）
- ✅ 首启解压 runtime（assets/runtime.bin 55MB → filesDir/meow-runtime 约 257MB）
- ✅ pi 进程通过 `/system/bin/linker64` 拉起（untrusted_app 直接 exec 自解压 ELF 报 EACCES，linker 加载可行）
- ✅ 终端页 `echo 喵~` → 输出 `喵~` + `（exit 0）`，RPC bash 全链路通
- ✅ 终端页标题从 y=58 下移到 y=165（insets 修复生效），显示「● 运行中」

**代码审查与修复（2026-08-11）**：
- ✅ M2.2-M2.6 全量代码审查（CodeReview 子代理），🔴/🟡 问题已全部修复并重新构建通过
- ✅ 聊天空回复排查：API Key 写入损坏（adb 重新写回修复）+ 「Connection error.」真正 root cause = **AndroidManifest 未声明 INTERNET 权限**（沙箱内 socket 创建直接 EPERM），补权限后全链路打通（见踩坑 8）
- ✅ 键盘弹起时发送/执行按钮被顶到状态栏后点不到 → 输入框加 Send IME action 修复
- ✅ 聊天流式实测通过（真机）：DeepSeek 流式出字（text_delta）+ thinking 折叠卡片 + 停止/重试事件流完整
- ✅ build-runtime.sh 加固：Termux 无 ldd → 改用 greadelf 枚举 NEEDED 迭代补齐传递依赖；拷贝 pi 包保留 @earendil-works scope 目录；dns-shim 自动入包

**关键技术发现（务必保留，踩坑记录）**：
1. **AGP 对 assets 的 `.gz` 后缀会自动解压并改名**（runtime.tar.gz → 190MB 的 runtime.tar，代码找不到文件）。解法：assets 改用无歧义扩展名 `runtime.bin`（内容仍是 gzip 流）。
2. **node 不是静态链接**：依赖 libz/libcrypto/libssl/libcares/libsqlite3/libffi/libicu*/libc++_shared 等 Termux 动态库。打包时必须 `cp -L` 解引用 symlink 拷贝真实 .so 进 runtime/lib，启动时设 `LD_LIBRARY_PATH`。
3. **untrusted_app 直接 exec 自解压 ELF 报 `Permission denied`（error=13）**：改用 `/system/bin/linker64 <node路径> <cli.js> ...` 作为 ProcessBuilder 命令即可。
4. **pi 的 bash 工具 fallback 到 `sh -c`**：App 进程 PATH 必须含 `/system/bin`，否则 spawn sh 失败 hang 住。
5. **可变 data class 更新 StateFlow 不触发重组**（新旧 List 中元素同一引用）：TerminalEntry 改不可变 + copy 更新。
6. **RuntimeManager.start() 需 Mutex 互斥**：防并发触发两次解压。
7. zstd-jni 1.5.6-4 不含 Android 原生库（Android 上 UnsatisfiedLinkError）→ 放弃 zstd 用 gzip。
8. **AndroidManifest 缺 `INTERNET` 权限时，App 沙箱内所有 socket 创建直接 EPERM**（连裸 TCP 都不行，`nc` 报 Operation not permitted），node/undici 把错误包成「Connection error.」且丢失底层 code，极易误判为 DNS/TLS 问题。排查技巧：`run-as <包名> nc -w3 <IP> 443` 先测裸连通性。（附带保险：内置 `runtime-assets/dns-shim.js` 钩住 dns.lookup 失败时直连公共 DNS 兜底，经 `NODE_OPTIONS=--require lib/dns-shim.js` 注入。）
9. **PowerShell/adb 重定向写文本文件会混入 CRLF**：cert.pem（CA 束）被 CRLF 污染后 node 解析失败，TLS 全挂。写证书/配置类文件必须保证 LF（base64 传输或显式控制行尾）。
10. **`SSL_CERT_FILE` 不被 node 读取**（那是 OpenSSL/Python 系的变量）；node 要用 `NODE_EXTRA_CA_CERTS` 额外 CA + `OPENSSL_CONF` 重定向 OpenSSL 配置路径（Termux 默认路径在 App 沙箱不可读，指到内置空 openssl.cnf）。
11. **Termux 无 ldd**（bionic 环境，binutils 只带 g 前缀工具）：枚举动态库依赖用 `greadelf -d <bin> | grep NEEDED`，且需迭代补齐传递依赖（如 libicui18n → libicuuc）；系统库（libc/libm/libdl）不在 $PREFIX/lib 自动跳过。
12. **拷贝 npm scope 包注意保留 scope 目录**：`cp -rL .../node_modules/@earendil-works/pi-coding-agent <dest>/node_modules/` 会丢掉 @earendil-works 层，必须先 `mkdir -p <dest>/node_modules/@earendil-works` 再拷入。

**三档常驻开关实测（2026-08-11，真机）**：
- ✅ 关闭档：退后台 → RuntimeManager.stop()，进程退出（30s 内 ps 无残留）
- ✅ 有限保活档：退后台 → 进程存活 + WorkManager SystemJobService 心跳已调度；延迟停止 handler 用 `coerceIn(1,1440)` 兜底防 0/负值；设置页保活时长可选 15/30/60 分钟
- ✅ 一直常驻档：退后台 45s+ → 前台服务存活（dumpsys isForeground=true，通知 channel=pi_runtime）
- ✅ 手动停止按钮：设置页「停止后台服务」→ stopPi + markStopped，前台服务消失

**APK 体积实测（2026-08-11）**：debug APK **105MB**（含 runtime.bin 52.9MB gzip；dex 合计 ~45MB 未开 R8），低于 200MB 红线 ✅；release 构建预期更小。

**APK 体积实测（2026-08-16，WSL 构建）**：debug APK **86MB**（含 runtime.bin 66.6MB gzip；dex 11 个 raw 合计 59.6MB、zip 压缩后仅 17.8MB），低于 200MB 红线 ✅。比 8-11 小 19MB 的主因是 dex 在包内为 deflate 压缩（8-11 记的 45MB 是未开 R8 的原始大小口径）；runtime.bin 反而从 52.9 → 68MB 变大（M3/M4 插件增多）。

**待完成（M2.7 剩余）**：
- ✅ 聊天页流式对话实测（2026-08-11 通过：DeepSeek 流式出字 + thinking 折叠 + 完整事件流）
- ✅ 三档常驻开关实测（2026-08-11 通过，见上方实测记录）
- ✅ APK 体积核对（debug 105MB，低于 200MB 红线）
- ✅ git commit 本轮 M2.2-M2.6 全部代码（commit 72ad9d6）
- ⏳ 更新 PLAN.md / 决策文档实测数据（体积、启动耗时、保活效果）
- ⏳ 停止生成（abort）/ 工具卡片补测（可选）

**下一步建议**（新会话从这里继续）：
1. 更新 PLAN.md 里程碑 M2 标记完成 + 决策文档实测数据 ✅（已做，见 git log 8a8c797）
2. 停止生成/工具卡片补测（可选）
3. 进入 M3（体验优化 + 文件管理 + 真终端）规划 ⭐ 主人 2026-08-11 指示：知识库后置

---

*—— 主人呜咕的专属喵喵助手 · 樱茈 🐾*
