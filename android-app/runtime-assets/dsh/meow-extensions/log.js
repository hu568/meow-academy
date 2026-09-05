/**
 * meow-jsonrpc 日志通道（plan-meow-jsonrpc-refactor 第 1 步，① 的注入缝）。
 *
 * 纯函数区（persona/快照/stats/model/errors 等）拿不到 cordis 的 ctx，这里提供
 * 模块级 sink + 一次性接线：apply() 第一行 `setLogger(ctx.logger)`，其余函数签名零改动。
 *
 * 双通道设计（plan §七 实测记录的纠偏：单靠 ctx.logger 达不成观测性验收）：
 *   - ctx.logger.warn：与插件既有 6 处留痕同通道（cordis 内置 LoggerService，落内存
 *     buffer——cordis 默认无控制台 exporter，logger-console 插件又只写 stdout）；
 *   - process.stderr.write：真正的可见出口。stdout 专用于 JSON-RPC 帧（cordis.yml:2），
 *     stderr 不在其列；真机部署里 DSH 的 stderr 全在终端页（plan-bugfix §2.5），
 *     Kotlin 侧 DshRuntimeService 读 stderr 的出口计划已点名「另案」。
 *
 * ⚠️ 帧纪律：**绝不 console.log / 不写 stdout**——任何 stdout 输出都会打碎 JSON-RPC 帧。
 */

let logger

/** 一次性接线：apply() 首行调用；logger 缺方法时该通道静默降级（与 `ctx.logger?.warn?.()` 同语义） */
export function setLogger(value) {
  logger = value
}

function sink(msg, err) {
  const line = err ? `${msg}：${err.message}` : msg
  logger?.warn?.(line)
  // 与 logger-console 插件的行格式对齐（[W] 前缀），但不依赖它（本模块自带前缀与换行）
  process.stderr.write(`[W] ${line}\n`)
}

/** warnOnce 的去重记录：同一 key 只报一次（高频路径防刷屏；key 取有限集合，如 `preset:<sessionId>`） */
const seen = new Set()

/** 去重留痕：同一 key 只报一次（高频路径防刷屏） */
export function warnOnce(key, msg, err) {
  if (seen.has(key)) return
  seen.add(key)
  sink(`meow-jsonrpc: ${msg}`, err)
}

/** 普通留痕：低频路径用；调用方传不带前缀的 msg（前缀在这里统一加） */
export function warn(msg, err) {
  sink(`meow-jsonrpc: ${msg}`, err)
}
