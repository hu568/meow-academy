# dsh-fork — 喵仓对上游 DeepSeek Harness 的源码改动（patch 形态入库）

`dsh/`（仓库根）是从 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
fork 出来的**本地源码副本，整体 gitignore 不入库**。本目录把 fork 里动过的每一个源码文件
固化为单个 `git format-patch`，保证任何人 clone 本仓库后都能精确复现出能打 `runtime.bin` 的 DSH。

## 基线

| 项 | 值 |
|---|---|
| 上游基线 commit | `47f943859bef60e4160492346772ded9b24f765a` |
| 基线日期 | 2026-08-13（Merge PR #2519 feat/npm-public） |
| 对应版本 | 0.1.0-rc.5（与真机 runtime.bin 内各包 package.json 一致） |

> 基线定位方法：fork 无 git 历史，用「全树 blob 匹配率扫描」锁定——4542 个源码文件中
> 该提交匹配 4490 个（98.9%），断崖式领先第二名，即 fork 当时的拷贝点。

## patch 内容（0001-meow-fork-on-deepseek-harness-47f943859b.patch）

### Android 存活适配
- **`packages/fs/fs-local/src/fsio.ts`** — 原子写「创建新文件」分支由 `link()` 硬链接发布改为
  `rename()`（SELinux 禁 untrusted_app 域 `link()`，否则 write/create 工具 EACCES）。
- **`packages/fs/fs-local/src/index.ts`** — 新增 `deny` 配置（`{path}[]`，文件精确 + 目录前缀），
  在 `resolve()` / `lstat()`（含 realpath 防符号链接绕过）/ `listDir()` 三处拦截，
  命中抛 `FS_PERMISSION_DENIED`。
- **`packages/shell/bash-local/src/index.ts`** — ① `DSH_BASH_BIN` 存在时经
  `[linker64, bash, --norc, --noprofile, -c]` 拉起 bash（untrusted_app 不能直接 exec 私有 ELF）；
  ② spawn env 追加 `DEEPSEEK_API_KEY: undefined` tombstone，bash 子进程拿不到明文 key。

### llm-pi-ai 模型管理配合（dormant 路由）
- **`src/catalog.ts` / `src/config.ts`** — 无 catalog 默认且 `models` 为空的路由不再报错，
  改为休眠存储（先存 provider 凭据、后填模型列表；填上即激活）。
- **`src/index.ts`** — discovery 时 `MISSING_CREDENTIAL` 视为无凭据（返回 undefined），不炸探测流程。
- **`tests/adapter.spec.ts` / `discovery.spec.ts` / `dynamic-config.spec.ts`** — 配套测试更新。
- **`README.md` / `README.zh.md`** — 上游为「文档即规格」风格，两段行为描述随代码同步更新。

### 新包 web-search-tavily
- **`packages/web/web-search-tavily/`**（src ×4 + tests ×2 + README ×2 + package.json + tsconfig）
  Tavily 网络搜索 provider（`TAVILY_API_KEY`）。
- **`packages/bundle/base/cordis.patch.yml` + `package.json`** — 注册 `dsh-web-search-tavily`。
- **`tsconfig.host.json`** — 加 project reference。

### 安卓运行时闭包清单
- **`deploy/meow-runtime/package.json`** — pnpm deploy 清单：以官方 python/sdk-runtime 闭包为基，
  剔除 ACP/subagent 驱动/node-pty/sandbox 原生模块(landlock)/query/lsp/mcp 等；
  含 SQLite 会话持久化与 DeepSeek web 搜索。**这是打闭包的输入，必须存在。**
- **`pnpm-workspace.yaml`** — 注册 `deploy/meow-runtime` + pin `@smithy/core@3.33.1`。

## 从零复现 runtime.bin

```bash
# 0. 前置：PC 有 node + pnpm；安卓侧需 JDK 17 + Android SDK + 真机（Termux 打包用）
git clone https://github.com/deepseek-ai/deepseek-harness dsh
cd dsh
git checkout 47f943859bef60e4160492346772ded9b24f765a
git apply ../android-app/runtime-assets/dsh-fork/0001-meow-fork-on-deepseek-harness-47f943859b.patch
pnpm install          # lockfile 由 install 重新生成（patch 不含 pnpm-lock.yaml）

# 1. PC 打 DSH 闭包（产物 .tmp/dsh-closure.tar.gz）
cd .. && bash android-app/runtime-assets/build-dsh-closure.sh

# 2. 推到真机 Termux 打 runtime.bin（见仓库根 AGENTS.md「重打 runtime」两步）
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

## 升级上游时

1. 新基线上重放本 patch（冲突则手工合并）；
2. 全量对比 fork 与新基线，重新生成 patch 并**更新本 README 的基线 commit**；
3. 重跑 `pnpm install` + 闭包脚本 + 真机打包验证。

## 已知非源码差异（不入 patch，属本地产物）

fork 与基线逐文件对比时还会看到：删除的 `.github`/`.agents`(16M)/`.claude`（上游 CI 与
agent 配置）、`CLAUDE.md`×4 与 examples 测试快照的本地化改写、`mise.toml`、`apps/web/dist`、
`*.tsbuildinfo`、空的 `dsh/dsh/.tmp` 构建残留——均不影响闭包构建与运行时行为。
