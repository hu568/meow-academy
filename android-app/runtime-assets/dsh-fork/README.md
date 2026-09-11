# dsh-fork — 喵仓对上游 DeepSeek Harness 的源码改动（patch 形态入库）

`dsh/`（仓库根）是从 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
fork 出来的**本地源码副本，整体 gitignore 不入库**。本目录把 fork 里动过的每一个源码文件
固化为单个 `git format-patch`，保证任何人 clone 本仓库后都能精确复现出能打 `runtime.bin` 的 DSH。

## 基线

| 项 | 值 |
|---|---|
| 上游基线 | tag `dsh-v0.1.5-rc.2`（commit `fb2c4b9e69`） |
| 基线日期 | 2026-09-11 升级（上一基线 `dsh-v0.1.1-rc.2` = `b150a551b8`，2026-08-23；再之前 rc.1 = `528c682e06`，首次 fork 0.1.0-rc.5 = `47f943859b`） |
| 对应版本 | 0.1.5-rc.2 |
| 旧系列 | `archive-rc2/`（rc.2 基线的 0001~0007，仅供复现旧 APK 0.3.0 及更早） |

> 升级记录（2026-09-11）：rc.2 → 0.1.5-rc.2（3225 commits / 20 天）。上游这 20 天里发生了
> **三处命中喵仓命门的破坏性变更**（删 SQLite 会话 provider 改 JSONL-only、删 agent-spine-demo、
> 删 examples/jsonrpc-demo 宿主入口），因此本次不是「改个 tag」而是移植项目：patch 系列
> **全部重做**（旧 0001 拆成 4 条，0002~0007 重放，另新增 2 条安卓回退）。
> 评估/执行计划与阶段记录见 `plan/plan-dsh-upgrade-0.1.5.md`；`cordis.yml` 组合重建见该文档 §4。

## patch 内容（9 条，按序 `git apply`）

| # | patch | 内容 |
|---|---|---|
| 0001 | `android-runtime-adaptations` | Android 存活适配基座（0.1.5 重做）：attachment-local 的 `ensureDurableHome` 祖先 fsync 容错 + 两处 `link()` EACCES/EPERM 回退（发布改 `lstat`+`rename`，别名发布退化 `COPYFILE_EXCL` 副本）；bash-local 的 `DSH_BASH_BIN` linker64 启动 + `DEEPSEEK_API_KEY` tombstone；subprocess-local 的 koffi 惰性解析；fs-local 的 guarded-create 发布原语改「`lstat` 探测 → `EEXIST`/`rename`」 |
| 0002 | `fs-local-deny-realpath` | fs-local `deny` 规则（文件精确 + 目录前缀，`resolve`/`lstat`/`listDir` 三处拦截）+ **realpath 双形态**比对（规则取词法 + 最近存在祖先 realpath；目标比 `targetKey` 与 `displayPath` 两面）——安卓部署根经挂载别名到达，只比词法会让名单整体空转 |
| 0003 | `llm-pi-ai-model-management` | llm-pi-ai 适配：`assertServiceable` 写入走 `deferred`（模型管理 UI 允许「先存凭据/路由、后填模型列表」，profile 存为休眠路由 + `catalogError` 诊断）；模型探测把「已存 apiKeyEnv 但凭据缺失」按未认证处理；两个 README 同步标注 fork 差异 |
| 0004 | `web-search-tavily-provider` | 新包 `web-search-tavily`（Tavily 搜索 provider，`TAVILY_API_KEY`）+ `bundle/base` 注册 + `tsconfig.host.json` 工程引用 |
| 0005 | `agent-preset-str-replace-editor-cwd` | tool-str-replace-editor 支持会话 cwd（按 `exec.agent.session.header.cwd` 解析相对路径）——喵仓「会话按工作区隔离」依赖它 |
| 0006 | `subprocess-local-android-platform` | `createProcessInspector` 的平台门认 `android`（安卓内核即 Linux，复用 LinuxProcessInspector）——持久 PTY bash 首调不再抛 unsupported |
| 0007 | `tool-cordis-headless-prompt` | `CORDIS_SYSTEM_PROMPT` 顶部加 headless 部署适配段（只写 code.host、`cordis_run` 同步激活、外观诉求不写插件；Web 前端已砍、host-only 全程无审批） |
| 0008 | `session-persistence-jsonl-android` | JSONL 会话持久化两条安卓硬阻塞：① 新增 `src/publish.ts` 的 `publishLinkNoReplace`（`link()` 被 SELinux 拒 → `lstat` 探测 + `rename` 发布，目标存在仍报 `EEXIST`），`materializePosix` 与 generation 的 `publishCurrentExclusive` 都改走它；② `lease.ts` 租约回退——内核 flock 无 android 预编译（`ERR_FLOCK_UNSUPPORTED_PLATFORM`），退回「进程内互斥 + `O_EXCL` 锁文件（写 pid，持有者消失可接管）」 |
| 0009 | `runtime-closure-manifest` | `deploy/meow-runtime/package.json` 按 0.1.5 重生成（139 项闭包，见下）+ `pnpm-workspace.yaml` 注册 + `tsconfig.host.json` 引用 + `THIRD_PARTY_NOTICES.md`/`scripts/gen-third-party-notices.ts` 记录 `@img/sharp-wasm32`（libvips wasm 含 LGPL-3.0 组件）+ lockfile |

**闭包清单（0009）的生成口径**：以 `cordis.yml` 行集 + 四预设行集 + 手写宿主入口
（`runtime-assets/dsh/host.mjs`、`meow-extensions/*.js`）的 import 面为根，对工作区
`package.json` 的 `dependencies + optionalDependencies + peerDependencies` 做 BFS；剔除上游
CLI/ACP/hooks/MCP/pwsh/workflow-ralph/sandbox-enforcer（landlock）/exa-perplexity 等喵仓不挂的行；
手工补 `dsh-sdk-jsonrpc-server`、`dsh-sdk-protocol`（手写入口 import，工作区遍历看不到）与
`@img/sharp-wasm32`（版本必须与 `sharp` 依赖声明完全一致，否则 hoisted 同名冲突 → deploy 静默丢包）。

## 0.1.5 上游破坏性变更与喵仓落位（重推 patch 前必读）

1. **会话持久化改 JSONL-only**：`dsh-session-persistence-sqlite` 被删，`session-format` 迁到 V3，
   迁移链在 `session-format-catalog/src/generated.ts` 编译期固化。旧 `chat.db`（SCHEMA_VERSION 17）
   新版**读不了** → 旧会话在 App 里仍可看历史气泡（Room 是另一套存储），但 DSH 侧 `resume` 落空。
   Android 硬阻塞两条全部由 0008 修掉。
2. **`dsh-agent-spine-demo` 被删**（`packages/examples` 整组删除，不保留别名）：它原来的职责在
   `cordis.yml` 里拆成显式行（`agent`/`agent-loop`/`tools`/`system-prompt`/`jobs`/`tool-*`）；
   persona 迁到 `system-prompt` 行的 `personaPrefix`（`dsh-persona` 是 scope-only 行，挂全局会
   fail loud，只在预设组合内可选使用）。
3. **`dsh-sdk-jsonrpc-demo` 被删**（喵仓旧宿主入口 `lib/packaged-bin.js`）：喵仓自建
   `runtime-assets/dsh/host.mjs`（复刻原 `runner.ts` 语义：显式配置路径 + `bareModuleBaseUrl` + stdin
   EOF/SIGTERM 时 dispose 退出），`terminal-host.js` 与 `build-runtime.sh` 已改指它。
4. **agent-presets 不再导出 `UnknownPresetError`/`PresetMountError`/`resolveSessionPreset`**：
   改 `RemoteError{code,details}`（稳定 code：`agent-preset/not-found|invalid|locked|read-only`）；
   会话预设重建改读 `agentPreset` 投影（`agentPresetProjectionDefinition.init/apply`，header 是创建时值、
   空白期切换以 `agent-preset/selected` 事件为准）。**注意**：这三个符号是 `meow-extensions/*.js`
   用的，`git grep` 在 `packages/` 里搜不到 —— 必须跑下面「PC 冒烟」才能抓到。
5. **`isTokenDelta` 从 `@deepseek-ai/dsh-llm/message` 搬到 `/assistant-stream`**（子路径仍在、
   但不再导出它 → ESM 链接期报错、DSH 直接起不来）。
6. **`userQuestions` 从「全局单 provider 槽」改 agent 作用域 waterfall 事件**
   （`ctx.on('user-questions/request', request => answerer.ask(request))`；祖先作用域的监听器能收到
   派发到后代 key 的事件）。旧 `registerProvider` 已不存在。
7. **settings 服务不再有通用 `mutate`/ns 视图 `describe`**：远程写入改走官方
   `@deepseek-ai/dsh-api-settings-controller`（`describe/update/replace/mutate`，Web Models 页同一条路径），
   需在 `cordis.yml` 挂 `settings-controller` 行。
8. **`subprocess-local` 的进程检查器平台门不认 `android`**（0006）；**`subprocess-local` 顶层
   koffi 静态加载**（0001）；**jsonl 用 `link()` 发布 + 原生 flock 租约**（0008）。

## 从零复现 runtime.bin

```bash
# 0. 前置：PC 有 node + pnpm（node ^22.19||>=24）；安卓侧需 JDK 17 + Android SDK + 真机 Termux
#    （Termux 需 nodejs-lts + bash + binutils + termux-keyring；打包脚本用预编译 meow-exec.so，免 clang）
git clone https://github.com/deepseek-ai/deepseek-harness dsh
cd dsh
git checkout dsh-v0.1.5-rc.2
for p in ../android-app/runtime-assets/dsh-fork/000*.patch; do git apply "$p" || { echo "✗ $p"; break; }; done
pnpm install --store-dir <你的 pnpm store>     # lockfile 随 0009 入库；install 幂等校验

# 1. PC 构建 + 打闭包（产物 .tmp/dsh-closure.tar.gz，用绝对路径传入更稳）
npm run build:lib
cd .. && bash android-app/runtime-assets/build-dsh-closure.sh "$(pwd)/.tmp/dsh-0.1.5" "$(pwd)/.tmp/dsh-closure.tar.gz"

# 2. 推到真机 Termux 打 runtime.bin（ssh/scp 或 adb 中转均可）
scp -P 8022 .tmp/dsh-closure.tar.gz \
    android-app/runtime-assets/{build-runtime.sh,terminal-host.js,dns-shim.js,meow-exec.c} \
    u0_a169@192.168.0.18:~/
scp -P 8022 .tmp/meow-exec-v4.so u0_a169@192.168.0.18:~/meow-exec.so   # 预编译，免 Termux clang
ssh -p 8022 u0_a169@192.168.0.18 'cd ~ && MEOW_EXEC_SO=$HOME/meow-exec.so bash build-runtime.sh dsh-closure.tar.gz'
scp -P 8022 u0_a169@192.168.0.18:~/runtime.bin android-app/app/src/main/assets/runtime.bin

# 3. 构建 APK（必须 clean：增量打包会留零填充垃圾让 APK 膨胀一倍）
cd android-app && ./gradlew clean assembleDebug
```

其余已入库的配套源料（无需额外步骤）：`runtime-assets/dsh/cordis.yml`（喵仓组合）、
`runtime-assets/dsh/host.mjs`（宿主入口）、`runtime-assets/dsh/meow-extensions/*.js`
（meow-jsonrpc + log/model/errors/stats 子模块）、`runtime-assets/terminal-host.js`、
`runtime-assets/dns-shim.js`、`runtime-assets/tools/fix-closure-links.mjs`、`runtime-assets/build-*.sh`。

## PC 冒烟（强烈建议，重推 patch 后先跑）

`cordis.yml` 的宿主入口支持 **stdio 模式**（未设 `DSH_JSONRPC_SOCKET` 时沿用 stdin/stdout），
所以闭包在 PC 上就能整树启动并跑 JSON-RPC，不必等真机：

```bash
mkdir -p .tmp/smoke && tar -xzf .tmp/dsh-closure.tar.gz -C .tmp/smoke
cp -r android-app/app/src/main/assets/dsh-presets .tmp/smoke/files/
# 起宿主（env 指向 .tmp/smoke 下的临时目录），然后按行发 JSON-RPC：
#   initialize → presets/list → llm/models → settings/setProvider（空 models = 休眠路由）
#   → session/query（不存在的会话）→ session/prompt（真 key 可跑完整回合）
env DSH_HOME=$PWD/.tmp/smoke/home DSH_CWD=$PWD/.tmp/smoke/ws DSH_FILES_DIR=$PWD/.tmp/smoke/files \
    DSH_SESSION_DIR=$PWD/.tmp/smoke/sessions DSH_SETTINGS_PATH=$PWD/.tmp/smoke/dsh-settings.json \
    DSH_CREDENTIALS_PATH=$PWD/.tmp/smoke/dsh-credentials.yaml DEEPSEEK_API_KEY=<key> \
    node .tmp/smoke/dsh/host.mjs .tmp/smoke/dsh/cordis.yml
```

**2026-09-11 实测价值**：本步骤一次抓出 3 处计划漏报的断链（`PresetMountError`、
`userQuestions.registerProvider`、settings 写入 API），全部是 `packages/` 里搜不到的
`meow-extensions/*.js` 侧用法 —— **PC 冒烟是重推 patch 后的必跑项**。

**PC 冒烟测不了安卓 flock 租约**：linux 上 `loadBinding()` 会去找原生 `system.node`
（闭包不含原生二进制 → `Cannot find module .../system.node`）。想验证可移植回退，可在
**解压出的闭包副本**里把 `node_modules/@deepseek-ai/node-addon-system/lib/flock.js` 的
`loadBinding()` 首行改成抛 `ERR_FLOCK_UNSUPPORTED_PLATFORM`（模拟安卓平台门），
完整回合即可跑通（2026-09-11 实测）。

## 已知环境坑（复现时必读）

- **node-prune 会经文件共享误伤 workspace**：`build-dsh-closure.sh` 的 node-prune 在产物上
  裁剪 doc/ 时，会把 workspace 里 `yaml@2.9.0/dist/doc/` 十个文件一并删掉（症状：lint/typecheck 过、
  运行时崩 `Cannot find '../doc/directives.js'`）。脚本已内置自愈（从完整产物反向补回，幂等）。
  同类误伤还会打掉 `pdfjs-dist` 的 LICENSE → 上游 third-party 门禁在客户端 bundle 上会失败
  （与 runtime 无关，提交时 `--no-verify` 跳过即可）。
- **闭包脚本的路径必须绝对**：脚本为 `pnpm deploy` 会 `cd` 进 DSH checkout，传相对路径会让后半段
  的相对引用二次拼接（yaml 自愈步骤曾因此静默失配、被 `set -e` 拦在 tar 之前）。脚本现已自行
  归一化 `DSH_ROOT`/`OUT_FILE`，但调用时用绝对路径最稳。
- **PC 冒烟测不出平台原生模块问题**：koffi/node-pty/sharp 在 PC x64 上能加载，Termux arm64+bionic
  才暴露。跨平台改动务必以真机启动为准。
- **替换 runtime.bin 后必须 `./gradlew clean assembleDebug`**：增量打包会让 APK 膨胀一倍
  （~70MB 零填充垃圾），clean 后恢复正常体积。
- **会话数据格式断代（0.1.5 起）**：旧 `chat.db` 不再被读取，旧会话在 DSH 侧 `resume` 会落空
  （新会话从空上下文开始）。prune 前的旧库留在 `.dsh-sessions/chat.db`，导出器见
  `plan/plan-dsh-upgrade-0.1.5.md` §6（轨道 A 预备项①，尚未落地）。

## 升级上游时

1. 新基线上重放本 patch（冲突则手工合并）；
2. **跑 PC 冒烟**（上节）——`meow-extensions/*.js` 的 import/服务 API 漂移只有它能抓到；
3. 全量对比 fork 与新基线，重新生成 patch 并**更新本 README 的基线 commit**；
4. 重跑 `pnpm install` + `npm run build:lib` + 闭包脚本 + 真机打包 + `clean assembleDebug`；
5. 真机回归按 `plan/plan-dsh-upgrade-0.1.5.md` §7 台账 11 项逐条走。

## 已知非源码差异（不入 patch，属本地产物）

fork 与基线逐文件对比时还会看到：删除的 `.github`/`.agents`(16M)/`.claude`（上游 CI 与
agent 配置）、`CLAUDE.md`×4 与 examples 测试快照的本地化改写、`mise.toml`、`apps/web/dist`、
`*.tsbuildinfo`、空的 `dsh/dsh/.tmp` 构建残留——均不影响闭包构建与运行时行为。
