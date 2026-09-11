/**
 * 结构化 RPC 错误（plan-meow-jsonrpc-refactor 第 4 步自 meow-jsonrpc.js 外迁；纯 JS、无 cordis 依赖）。
 *
 * ⚠️ 跨语言错误码对齐清单（plan §0.5）：改数值任一侧必须同步——
 *   - -32001 PRESET_UNKNOWN      → App 侧 ui/chat/ChatStreamingContent.kt（ERR_PRESET_UNKNOWN，气泡带 data.available）
 *   - -32002 PRESET_MOUNT_FAILED → App 侧 ui/chat/ChatStreamingContent.kt（ERR_PRESET_MOUNT_FAILED，气泡带 data.detail）
 *   - -32003/-32004/-32005       → App 侧暂无硬编码（按通用错误渲染）；新增硬编码时在此登记
 *   JS 侧唯一出口 = 本文件 RPC_ERROR；MeowJsonRpcTransport 按 error.meowRpc 标记序列化 code/data。
 *
 * DSH 0.1.5 起 agent-presets 不再导出 UnknownPresetError/PresetMountError，改用 typert 协议的
 * `RemoteError{ code, message, details }`（判别只看 code 字符串，见 dsh-typert-protocol/remote-error.ts）。
 * 本文件按稳定 code 映射，保持 App 侧既有错误码与 data 形状不变。
 */

/**
 * 结构化 RPC 错误（plan-standard-mode §三.8 错误映射约定）。
 *
 * 官方 JsonRpcLineTransport 对 handler 抛错只回 `-32603 + message`；预设/命令类
 * 错误需要把稳定 code 与结构化 data（可用预设列表、挂载失败逐行原因等）送到 App，
 * 所以本插件用 MeowJsonRpcTransport（下方子类）识别 MeowRpcError 并序列化
 * `error.code`（-32001..-32005 服务器自定义区段）与 `error.data`。
 */
export const RPC_ERROR = {
  /** 请求的 Agent 预设不存在；data.available = 名单里实际可用的 id 列表 */
  PRESET_UNKNOWN: -32001,
  /** 预设存在但组合挂载失败（含会话已锁定、不可换预设）；data.detail = 底层原因 */
  PRESET_MOUNT_FAILED: -32002,
  /** commands 服务未挂载（斜杠命令通道不可用） */
  COMMAND_UNAVAILABLE: -32003,
  /** 命令行解析不到已注册命令（含旧会话未 join 预设、没有 /plan 的场景） */
  COMMAND_UNKNOWN: -32004,
  /** 内置（trust=system）预设不可删除 */
  PRESET_IMMUTABLE: -32005,
}

/** 构造一个带稳定 code/data 的 RPC 错误；由 MeowJsonRpcTransport 识别并结构化回传 */
export function meowRpcError(code, message, data) {
  const error = new Error(message)
  error.meowRpc = true
  error.rpcCode = code
  error.rpcData = data
  return error
}

/**
 * 把 agent-presets 域的 `RemoteError` 映射为结构化 RPC 错误；非预设异常原样重抛。
 * @param {unknown} error 捕获到的异常
 * @param {string[]} [available] 当前名单里可用的预设 id（调用方按需扫描后传入，供 App 气泡列出）
 * @returns {never} 总是以 throw 结束
 */
export function throwMappedPresetError(error, available = []) {
  // 跨 realm 用结构标记判别（isDSHRemoteError），与官方 remoteErrorOf 同款理由
  const structural = error !== null && typeof error === 'object' ? error : undefined
  if (structural === undefined || structural.isDSHRemoteError !== true) throw error
  const details = structural.details !== null && typeof structural.details === 'object' ? structural.details : {}
  const presetId = typeof details.agentPreset === 'string' ? details.agentPreset : null
  switch (structural.code) {
    case 'agent-preset/not-found':
      throw meowRpcError(RPC_ERROR.PRESET_UNKNOWN, structural.message, {
        code: 'PRESET_UNKNOWN',
        presetId,
        available: [...available],
      })
    case 'agent-preset/invalid':
    case 'agent-preset/locked':
    case 'gateway/bad-request':
      throw meowRpcError(RPC_ERROR.PRESET_MOUNT_FAILED, structural.message, {
        code: 'PRESET_MOUNT_FAILED',
        presetId,
        // invalid 带 reason（逐行/逐字段原因）；locked 与 bad-request 用 message
        detail: typeof details.reason === 'string' ? details.reason : structural.message,
      })
    case 'agent-preset/read-only':
      throw meowRpcError(RPC_ERROR.PRESET_IMMUTABLE, structural.message, {
        code: 'PRESET_IMMUTABLE',
        presetId,
        detail: typeof details.reason === 'string' ? details.reason : structural.message,
      })
    default:
      throw error
  }
}
