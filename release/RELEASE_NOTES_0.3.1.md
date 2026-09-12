# 喵仓 0.3.1 发行说明

## 添加功能

- DSH 运行时基线升级 `0.1.1-rc.2` → `0.1.5-rc.2`（3225 commits / 20 天）：带来会话自动起名、大结果落盘、超时策略、web-fetch-http、Typert 远程面等上游能力。
- 会话存储从 SQLite 换成 **JSONL（V3 + zstd）**，新增会话查询与投影缓存。
- 组合树显式成行（`agent` / `agent-loop` / `tools` / `system-prompt` / `jobs` / 各 `tool-*`），部署人格迁到 `system-prompt.personaPrefix`。
- 宿主入口自建 `runtime-assets/dsh/host.mjs`（上游删除 `dsh-sdk-jsonrpc-demo` 后）。
- `meow-jsonrpc` 扩展面按新 API 重做：agent 预设（错误按 `RemoteError.code` 映射）、问答通道（`user-questions/request` waterfall 答案器）、settings 写入（走官方 `settings-controller`）。

## 修复

- **聊天页「（空回复）」**：0.1.5 不再逐 delta 发 `assistant/chunk`，改为认 step 结束时的 `assistant/message` 为正文权威来源（幂等 upsert，保留已回填的工具结果）。
- **工具调用卡片错位 + 「工具调用 xN」翻倍**：改为按 `(turn, step)` 分桶归位，工具段按 callId 幂等 upsert、结果按 callId 回填。
- 安卓执行链 10 条 fork patch：JSONL 发布 `link()` → `lstat` + `rename`、flock → 可移植锁文件、koffi 惰性化、attachment 祖先 fsync 容错、subprocess 认 `android`、bash-local linker64 + key tombstone 等。
- fs-local 的 keep-out 规则改为 realpath 双形态比对（此前凭据 / 记忆文件在安卓上拦不住）。

## 优化

- 闭包清单按新组合树重推为 139 项，`@img/sharp-wasm32` 与 `sharp` 版本严格对齐（避免 pnpm hoisted 静默丢包）。
- 启动注入 `TMPDIR`（Termux 版 node 的 `os.tmpdir()` 指向编译期前缀路径，App 域不可写）。
- `build-dsh-closure.sh` 路径归一化（相对路径调用时 yaml 自愈步骤会静默失配）。
- 聊天链路新增 `AssistantMessageSegmentsTest` 9 例 + `TurnSegmentBuilderTest` 8 例，全套 107 tests PASS。
