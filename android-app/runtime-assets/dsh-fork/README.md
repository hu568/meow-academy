# dsh-fork — 喵仓对上游 DeepSeek Harness 的源码改动（patch 形态入库）

`dsh/`（仓库根）是从 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
fork 出来的**本地源码副本，整体 gitignore 不入库**。本目录把 fork 里动过的每一个源码文件
固化为单个 `git format-patch`，保证任何人 clone 本仓库后都能精确复现出能打 `runtime.bin` 的 DSH。

## 基线

| 项 | 值 |
|---|---|
| 上游基线 | tag `dsh-v0.1.1-rc.2`（commit `b150a551b8`） |
| 基线日期 | 2026-08-23 升级（2026-08-21 曾升级至 rc.1 = `528c682e06`；2026-08-13 首次 fork 于 `47f943859b` = 0.1.0-rc.5） |
| 对应版本 | 0.1.1-rc.2 |

> 升级记录（2026-08-23）：rc.1 → v0.1.1-rc.2（35 commits，主题=统一图片管线）。原 rc.1 patch
> 对 rc.2 `git apply --check` 零冲突全量重放；另新增 attachment-local 的 Android 适配（父目录
> fsync 与 `link()` 的 SELinux 规避，见下）与 `sharp-wasm32` 兜底依赖。SQLite SCHEMA_VERSION 仍为
> 17，**不删库、不迁移**。计划与过程见 `plan/plan-dsh-upgrade-rc2.md`。

## patch 内容（0001-meow-fork-on-deepseek-harness-dsh-v0.1.1-rc.2.patch）

### Android 存活适配
- **`packages/fs/fs-local/src/fsio.ts`** — guarded-create 发布原语由 `link()` 改为
  「`lstat()` 探测 → 命中即抛 EEXIST → `rename()` 发布」（SELinux 禁 untrusted_app 域
  `link()`）。探测保住 no-replace 契约：竞争者保留、`FS_NOT_OBSERVED` /
  `FS_NOT_REGULAR_FILE` 错误映射与上游 link() 语义完全一致（上游 rc.1 的全套
  竞争测试原样通过），仅存探测→发布的极小 TOCTOU 窗口，单进程 App 可接受。
- **`packages/fs/fs-local/src/index.ts`** — 新增 `deny` 配置（`{path}[]`，文件精确 + 目录前缀），
  在 `resolve()` / `lstat()`（含 realpath 防符号链接绕过）/ `listDir()` 三处拦截，
  命中抛 `FS_PERMISSION_DENIED`。
- **`packages/shell/bash-local/src/index.ts`** — ① `DSH_BASH_BIN` 存在时经
  `[linker64, bash, --norc, --noprofile, -c]` 拉起 bash（untrusted_app 不能直接 exec 私有 ELF）；
  ② spawn env 追加 `DEEPSEEK_API_KEY: undefined` tombstone，bash 子进程拿不到明文 key。
- **`packages/subprocess/subprocess-local/src/windows-inspector.ts`** — koffi 原生绑定改为
  首次调用惰性解析（type-only import + `createRequire`）。新版引入的 Windows 进程检查器在
  模块顶层就触达 koffi 原生绑定，而 koffi 无 Android/bionic arm64 prebuilt——静态 import
  会让 `dsh-subprocess-local` 在 Android 上插件树加载即崩（真机实测）。Linux 检查器纯走
  `/proc`，不受影响。

### llm-pi-ai 模型管理配合（dormant 路由）
- **`src/catalog.ts` / `src/config.ts`** — 无 catalog 默认且 `models` 为空的路由不再报错，
  改为休眠存储（先存 provider 凭据、后填模型列表；填上即激活）。
- **`src/index.ts`** — discovery 时 `MISSING_CREDENTIAL` 视为无凭据（返回 undefined），不炸探测流程。
- **`tests/adapter.spec.ts` / `discovery.spec.ts` / `dynamic-config.spec.ts`** — 配套测试更新。
- **`README.md` / `README.zh.md`** — 上游为「文档即规格」风格，两段行为描述随代码同步更新。

### 新包 web-search-tavily
- **`packages/web/web-search-tavily/`**（src ×4 + tests ×2 + README ×2 + package.json + tsconfig）
  Tavily 网络搜索 provider（`TAVILY_API_KEY`）。
- **`tests/redirect.spec.ts`** — 平台对照组测试随 undici 安全策略更新：新版 undici 跨源重定向
  会剥离 Authorization（body 仍转发），故「显式拒绝 redirect」仍是唯一同时护住两者的姿态。
- **`packages/bundle/base/cordis.patch.yml` + `package.json`** — 注册 `dsh-web-search-tavily`。
- **`tsconfig.host.json`** — 加 project reference。

### 安卓运行时闭包清单
- **`deploy/meow-runtime/package.json`** — pnpm deploy 清单：以官方 python/sdk-runtime 闭包为基，
  剔除 ACP/subagent 驱动/node-pty/sandbox 原生模块(landlock)/query/lsp/mcp 等；
  含 SQLite 会话持久化与 DeepSeek web 搜索。**这是打闭包的输入，必须存在。**
  rc.2 起新增 `@deepseek-ai/dsh-attachment-local`（官方附件存储）与
  `@img/sharp-wasm32@0.35.3`（Termux sharp WASM 兜底）。
- **`pnpm-workspace.yaml`** — 注册 `deploy/meow-runtime`。

### 图片存储 Android 适配（rc.2 新增）
- **`packages/attachment/attachment-local/src/store.ts`** — 两处 Android/SELinux 规避：
  1. `ensureDurableDirectory()` 向上 fsync 祖先目录时，遇到 `EACCES`/`EPERM`（App 沙箱不能
     `open('/data/user/0')` 等数据根之上的父目录）改为 best-effort 停止，不再整体失败；
  2. `commitPreparedImageFile()` 的内容寻址发布由 `link()` 为主，遇 `EACCES`/`EPERM`（SELinux
     禁 untrusted_app 域 `link()`，与 fs-local 同坑）回退为「`lstat()` 探测 → 未命中
     `rename()` 发布 / 命中校验摘要」；发布后 `unlink(tmp)` 容忍 `ENOENT`（rename 后临时文件已不存在）。
- 说明：cordis.yml 挂载 `dsh-attachment-local`、meow-jsonrpc.js 新增 `session/attachImages` /
  `session/imageLimits` 属于 `runtime-assets/dsh/`（不入 patch）；sharp 在 Termux 靠
  `@img/sharp-wasm32` 自动 WASM 兜底（PC 闭包内 linux-x64 原生包在 arm64 上加载失败后 fallback）。

## 从零复现 runtime.bin

```bash
# 0. 前置：PC 有 node + pnpm（node ^22.19||>=24）；安卓侧需 JDK 17 + Android SDK + 真机（Termux 打包用）
git clone https://github.com/deepseek-ai/deepseek-harness dsh
cd dsh
git checkout dsh-v0.1.1-rc.2
git apply ../android-app/runtime-assets/dsh-fork/0001-meow-fork-on-deepseek-harness-dsh-v0.1.1-rc.2.patch
pnpm install          # lockfile 由 install 重新生成（patch 不含 pnpm-lock.yaml）

# 1. PC 构建并打 DSH 闭包（产物 .tmp/dsh-closure.tar.gz）
npm run build:lib     # workspace 包 files 字段只发布 lib/，必须先构建
cd .. && bash android-app/runtime-assets/build-dsh-closure.sh

# 2. 推到真机 Termux 打 runtime.bin（ssh 或 adb 均可，见 AGENTS.md「重打 runtime」）
adb push .tmp/dsh-closure.tar.gz android-app/runtime-assets/build-runtime.sh \
    android-app/runtime-assets/dns-shim.js /data/local/tmp/
adb shell 'cp /data/local/tmp/* ~/ && chmod +x ~/build-runtime.sh && ~/build-runtime.sh ~/dsh-closure.tar.gz'
adb pull /data/local/tmp/runtime.bin android-app/app/src/main/assets/runtime.bin

# 3. 构建 APK
cd android-app && ./gradlew clean assembleDebug
```

其余已入库的配套源料（无需额外步骤）：`runtime-assets/dsh/cordis.yml`（喵仓组合）、
`runtime-assets/dsh/meow-extensions/meow-jsonrpc.js`（session/bash/setModel/resume 扩展）、
`runtime-assets/terminal-host.js`、`runtime-assets/dns-shim.js`、
`runtime-assets/tools/fix-closure-links.mjs`、`runtime-assets/build-*.sh`。

## 已知环境坑（复现时必读）

- **WSL/Linux 上 node-prune 会误伤 workspace**：`build-dsh-closure.sh` 的 node-prune 步骤在
  产物上裁剪 doc/ 时，会经由文件共享机制把 workspace 里 `yaml@2.9.0/dist/doc/` 十个文件一并
  删掉（TS 编译用 .d.ts 还在、运行时 require .js 缺失——症状是后续 lint/typecheck 过但运行时
  崩 `Cannot find '../doc/directives.js'`）。脚本已内置自愈步骤（从完整产物反向补回，幂等）。
- **PC 冒烟测不出平台原生模块问题**：koffi/node-pty 等在 PC x64 上能加载，Termux arm64+bionic
  才暴露。跨平台改动务必以真机启动为准。
- **替换 runtime.bin 后必须 `./gradlew clean assembleDebug`**：增量打包会让 APK 膨胀一倍
  （~70MB 零填充垃圾），clean 后恢复正常体积。
- **SQLite SCHEMA_VERSION 17（rc.1 = rc.2）硬守卫**：rc.1 → rc.2 **不删库、不迁移**，旧会话可续聊；
  若未来上游 bump 到 >17，DSH 会因版本不匹配拒绝打开旧库，届时需按上游迁移或重建。

## 升级上游时

1. 新基线上重放本 patch（冲突则手工合并）；
2. 全量对比 fork 与新基线，重新生成 patch 并**更新本 README 的基线 commit**；
3. 重跑 `pnpm install` + `npm run build:lib` + 闭包脚本 + 真机打包验证。

## 已知非源码差异（不入 patch，属本地产物）

fork 与基线逐文件对比时还会看到：删除的 `.github`/`.agents`(16M)/`.claude`（上游 CI 与
agent 配置）、`CLAUDE.md`×4 与 examples 测试快照的本地化改写、`mise.toml`、`apps/web/dist`、
`*.tsbuildinfo`、空的 `dsh/dsh/.tmp` 构建残留——均不影响闭包构建与运行时行为。
