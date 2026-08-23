# 📊 聊天右侧功能看板 · 调用量统计（session/stats）

> 本文记录 2026-08-23 新增的 `session/stats` RPC、折叠算法口径与展示约定。
> 右侧看板其他两块（模型管理 / 快捷文件）见 `plan/plan-chat-dashboard.md`。

## 1. 为什么在 DSH 进程里算统计

- 会话日志存 DSH SQLite，内部 **zstd 压缩 + 私有 packed codec**（SCHEMA_VERSION=17），App 侧无法直接读库。
- 因此在 `meow-jsonrpc.js` 里新增 `session/stats` RPC：用官方 `persistence.load(SessionId(id))` 把磁盘日志解码成事件数组，再按 web 端同款投影折叠。
- 优点：杀 App 重开会话 resume 后，调用量仍完整（读磁盘日志，非进程内存）。

## 2. RPC 协议

### 请求

```json
{"jsonrpc":"2.0","id":"<uuid>","method":"session/stats","params":{"sessionId":"room-123"}}
```

### 响应

```json
{
  "jsonrpc": "2.0",
  "id": "<uuid>",
  "result": {
    "stats": {
      "turns": 7,
      "steps": 82,
      "llmMs": 414000,
      "toolMs": 81000,
      "ttftMs": 15200,
      "ttftSteps": 8,
      "decodeMs": 255000,
      "decodeTokens": 57200,
      "inputTokens": 8700000,
      "cacheReadTokens": 8600000,
      "cacheWriteTokens": 20000,
      "outputTokens": 57200,
      "lastStep": { "llmMs": 17000, "ttftMs": 2100, "decodeMs": 15000, "decodeTokens": 4600 },
      "context": { "usedTokens": 62400, "contextWindow": 128000 }
    }
  }
}
```

- 找不到会话 / 未持久化 / DSH 未初始化 → `{ "stats": null }`，**不抛错误**。
- `lastStep`：最近一条能折叠出完整计时与 token 的 `assistant/message`；没有则 `null`。
- `context`：分子分母都齐全才有；否则 `null`。

## 3. 字段语义与折叠口径

实现：`meow-jsonrpc.js` 的 `foldSessionStats(events)`（port `dsh-session-stats/projection.ts` + web StatsLine）。

| 字段 | 口径 |
| --- | --- |
| `turns` / `steps` | 以 `step/end` 为权威（finally 语义）；turn 变化才 `turns + 1` |
| `llmMs` | `assistant/message.time - step/start.time` 累计 |
| `toolMs` | `tool/result.time - tool/call.time`，按 `tool/result.message.source.callId` 配对 |
| `ttftMs` / `ttftSteps` | 首 token 判定 = `isTokenDelta(chunk)`（非空 `text-delta` / `reasoning-delta` / `tool-call-delta`）；`ttftMs += 首token.time - step/start.time`，`ttftSteps += 1` |
| `decodeMs` / `decodeTokens` | 有首 token 且 message 带 usage 时：`message.time - 首token.time` / `usage.outputTokens` |
| `inputTokens` / `outputTokens` / `cacheReadTokens` / `cacheWriteTokens` | `assistant/message.usage` 四个桶累计 |
| `lastStep` | 最近一条完整 `assistant/message` 的 `{ llmMs, ttftMs, decodeMs, decodeTokens }` |
| `context.usedTokens` | 最近一次 usage 样本的 prompt 侧 billed input = `input + cacheRead + cacheWrite`（与 token-meter `pressureFrom()` 一致） |
| `context.contextWindow` | 最近一条 `request/context` 事件的 `contextWindow` |

## 4. App 侧使用

- `DshRpcClient.sessionStats(sessionId, timeoutMs=15_000)` → `JsonObject?`
- `SessionUsageStats.parse(result)` → 数据类（`data/chat/SessionUsageStats.kt`）
- `ChatViewModel.sessionUsageStats: StateFlow<SessionUsageStats?>`，触发点：
  1. DSH `RuntimeState.Running`
  2. `runStream` 结束（`_streaming.value = null` 后）
  3. 右侧看板打开 / 切换会话（`ChatScreen` 的 `LaunchedEffect(currentId, dashboardOpen)`）

## 5. 展示约定

- 卡片风 dashboard：Hero 大数（轮/步）、LLM/工具时长卡、首 token/tok/s 卡、**缓存命中环形**、**上下文使用量环形**、Token 用量比例条、最新回合强调卡。
- 格式化函数在 `ui/chat/UsageStatsFormat.kt`（对齐 web StatsLine）：
  - `formatTokens`：517 / 12.2K / 517K / 1.2M
  - `formatDuration`：`1.9s` / `6m54s`
  - `formatTokensPerSecond`：≥10 取整，否则一位小数
  - `formatLatencySeconds`：<10 一位小数，否则取整
  - `formatRunDuration`：`17秒` / `1分23秒`
  - `cacheHitPercent`：v1 简单整数取整；非全命中却取整到 100 时显示 `99.9`
- 上下文环显示 `min(100, round(used/window*100))%`，副文字 `formatTokens(used) / formatTokens(window)`；任一分母缺失则隐藏上下文环。

## 6. 部署注意

改的是打进 APK 的 runtime 插件 → **必须重建 runtime.bin**（AGENTS.md 标准流程）：

```bash
bash android-app/runtime-assets/build-dsh-closure.sh
# adb push + Termux build-runtime.sh + adb pull 回 assets
cd android-app && ./gradlew clean assembleDebug   # 必须 clean，否则 APK 膨胀
```

设备/ Termux 不可用时：App 侧代码与构建可先行，调用量面板显示空态（RPC 返回 `stats: null` 或超时），真机验证延后。