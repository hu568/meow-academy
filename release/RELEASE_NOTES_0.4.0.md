# 喵仓 0.4.0（versionCode 13）发行说明

> 主题：**DSH 基线升级到 0.1.5-rc.2**（`dsh-v0.1.1-rc.2` → `dsh-v0.1.5-rc.2`，3225 commits / 20 天）
> 这是自 0.2.x 以来最大的一次底层换代：**会话存储从 SQLite 换成 JSONL（V3）+ zstd**，
> 组合树（`agent-spine` 拆除后重排）、宿主入口、agent 预设 / 问答 / settings 三条扩展面全部重做。
> 计划与执行记录：`plan/plan-dsh-upgrade-0.1.5.md`；patch 与复现：`android-app/runtime-assets/dsh-fork/README.md`。

## 升级内容

### 一、运行时基线 0.1.5-rc.2

- 组合 `runtime-assets/dsh/cordis.yml` 全量重排（66 行）：上游 `agent-spine-demo` 已删除，其职责显式成行
  （`agent` / `agent-loop` / `tools` / `system-prompt` / `jobs` / 各 `tool-*`）；部署 persona 迁到
  `system-prompt.personaPrefix`（人格层仍归角色库 + `meow:memory` section）。
- **宿主入口自建**：上游删除了 `dsh-sdk-jsonrpc-demo`，改用 `runtime-assets/dsh/host.mjs`
  （显式配置路径 + 闭包内 bare 解析 + stdin/SIGTERM 时先 dispose 再退出）。
- 会话持久化 `session-persistence-sqlite` → `session-persistence-jsonl`（+ `session-query-sqlite` /
  `session-projection` / `storage*` / 投影缓存）；`attachment-local`、`llm-pi-ai`、`settings` 等按新版 Config 对齐。
- 新增上游能力：Typert 远程面底座、`session-title`（模型起名）、`spill` 大结果落盘、`timeout-policy`、
  重复调用提醒、`web-fetch-http`、`command-feedback`、`plugin-package-inventory` 等。

### 二、安卓适配（10 条 fork patch，`dsh-fork/`）

1. **JSONL 发布/租约两条硬阻塞**：SELinux 禁 `link(2)` → `publishLinkNoReplace`（`lstat` 探测 + `rename`，
   目标存在仍报 EEXIST）；原生 flock 无 android 预编译 → 可移植锁文件回退（进程内互斥 + `O_EXCL` +
   pid 失效接管）。
2. **koffi 惰性化**（`linux-execve` / `windows-job` / `windows-inspector`）：否则插件树加载即崩。
3. **attachment-local**：祖先 fsync 容错 + link 回退 + `COPYFILE_EXCL` 别名；**fs-local**：deny 规则
   （realpath 双形态）+ 发布原语；**bash-local**：linker64 启动 + key tombstone；
   **subprocess-local**：进程检查器认 `android`；**llm-pi-ai**：写入 deferred（先存凭据后填模型）、
   探测凭据缺失按未认证；**tool-str-replace-editor**：会话 cwd。
4. **闭包清单**：139 项（按 `cordis.yml` 行集 + 四预设 + 手写入口 import 面 BFS 推导），
   `@img/sharp-wasm32@0.35.4` 与 `sharp` 严格对齐（不同版本会被 hoisted 冲突静默丢包）。

### 三、扩展面 API 漂移修复（`meow-extensions/`）

- `isTokenDelta` → `@deepseek-ai/dsh-llm/assistant-stream`；预设重建改读 `agentPreset` 投影
  （`agentPresetProjectionDefinition.init/apply`）。
- 预设错误改按 `RemoteError.code` 映射（`PRESET_UNKNOWN`/`PRESET_MOUNT_FAILED`/`PRESET_IMMUTABLE` 语义不变），
  失败时补扫名单供 App 气泡列可用预设。
- 问答通道：`registerProvider` → `ctx.on('user-questions/request', …)` waterfall 答案器（否则 ask_user 永久挂起）。
- settings 写入：`settings.mutate` → 官方 `@deepseek-ai/dsh-settings-controller`（新增 `settings-controller` 行），
  与 Web Models 页同一路径。

### 四、其他 App 侧

- `DSH_SESSION_DB` → `DSH_SESSION_DIR`（JSONL 根目录 `<filesDir>/.dsh-sessions`）；
- 注入 `TMPDIR`（Termux 版 node 的 `os.tmpdir()` 指向编译期前缀路径，App 域不可写 →
  `spill-local` 的 `mkdtemp`、subprocess runner 临时目录会 EACCES）；
- `build-dsh-closure.sh` 路径归一化（相对路径调用时 yaml 自愈步骤会静默失配）。

## 真机验收（RPC 探针）

核心链路 **10/11 ✅**：启动/initialize、四预设挂载、持久 PTY（跨回合 `export` → 读回）、
记忆/角色（memory 工具 ×3）、模型管理（休眠路由→激活→删除）、图片附件（sharp WASM 落盘）、
PTC `run_code`、杀进程 resume（`session.v3.jsonl.zstd` 落盘 + 新实例读回预设）。

- ⚠️ **apt 真实装机仍不可用**：`dpkg: error opening configuration directory '/data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d': Permission denied`
  （`--confdir`/`DPKG_CONF_DIR` 被忽略）。`apt-get update` 正常、已装命令可用——该限制升级前就存在（历史验证里的「安装」实为已装包 no-op），
  需要专门方案（见计划 §10.5）。
- ⚠️ **旧会话上下文断代**：旧 `chat.db`（SCHEMA_VERSION 17）新版不读。App 里历史气泡照常显示（Room 是另一套存储），
  但旧会话在 DSH 侧 resume 会从空上下文开始；§6 的导出器未落地。
- UI 层（聊天渲染/抽屉/看板）未在本次自动化里验收（真机处于密码锁屏），建议解锁后自查一轮。

## 体积

runtime.bin 78.5MB（旧 78.5MB 同级）｜APK 105.9MB（旧 102.4MB，+3.5MB 来自 `@img/sharp-wasm32`）。
