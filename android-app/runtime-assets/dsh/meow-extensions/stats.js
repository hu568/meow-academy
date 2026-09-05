/**
 * 会话调用量折叠（plan-meow-jsonrpc-refactor 第 4 步自 meow-jsonrpc.js 外迁）。
 * 纯函数、零 import：`isTokenDelta`（@deepseek-ai/dsh-llm/message）经 deps 注入——
 * 不把该 import 搬进来，换来任何环境可裸测（测试传 fake 判定即可）。
 */

/**
 * 会话调用量折叠（port `dsh-session-stats/projection.ts` 状态机 + web StatsLine 口径）。
 * 逐事件折叠持久化日志，产出 App 侧「功能看板」需要的全部原始分母。
 *
 * 关键口径：
 *  - turns/steps 以 step/end 为权威（finally 语义；turn 变化才 +1）；
 *  - LLM 时长 = assistant/message 时间 - step/start 时间；
 *  - 首 token = isTokenDelta 到的最早 chunk；decode = message - 首 token；
 *  - tool 时长 = tool/result 时间 - tool/call 时间（按 callId 配对）；
 *  - token 桶：inputTokens/outputTokens/cacheRead/cacheWrite 均为累计总和；
 *  - 上下文 = 最近一次 usage 样本的 prompt 侧 billed input / 最近 request/context.contextWindow。
 *
 * @param {Array<{seq:number,time:number,type:string,data:any}>} events 持久化会话日志
 * @returns {object} session/stats 的 stats 对象
 */
export function foldSessionStats(events, { isTokenDelta }) {
  let state = {
    turns: 0, steps: 0, llmMs: 0, toolMs: 0,
    ttftMs: 0, ttftSteps: 0, decodeMs: 0, decodeTokens: 0,
    inputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0, outputTokens: 0,
    lastTurn: null,
    openStep: null,          // { turn, step, startTime, firstTokenTime }
    pendingCalls: {},        // callId -> dispatch time
    lastStep: null,          // { llmMs, ttftMs, decodeMs, decodeTokens }
    lastPressureTokens: null, // number | null
    contextWindow: null,      // number | null
  }
  const usageTokensOf = (usage) => {
    if (typeof usage !== 'object' || usage === null) return null
    const input = usage.inputTokens
    const output = usage.outputTokens
    const cacheRead = usage.cacheReadTokens ?? 0
    const cacheWrite = usage.cacheWriteTokens ?? 0
    if (typeof input !== 'number' || !Number.isFinite(input) || input < 0) return null
    if (typeof output !== 'number' || !Number.isFinite(output) || output < 0) return null
    return { input, output, cacheRead, cacheWrite }
  }
  for (const event of events) {
    const d = event.data
    switch (event.type) {
      case 'step/start':
        state.openStep = { turn: d.turn, step: d.step, startTime: event.time, firstTokenTime: null }
        break
      case 'assistant/chunk': {
        const open = state.openStep
        if (open === null || open.turn !== d.turn || open.step !== d.step) break
        if (open.firstTokenTime === null && isTokenDelta(d.chunk)) {
          state.openStep = { ...open, firstTokenTime: event.time }
        }
        if (d.chunk?.type === 'usage') {
          const u = usageTokensOf(d.chunk.usage)
          if (u !== null) {
            state.lastPressureTokens = u.input + u.cacheRead + u.cacheWrite
          }
        }
        break
      }
      case 'assistant/message': {
        const open = state.openStep
        if (open !== null && open.turn === d.turn && open.step === d.step) {
          const llmMs = Math.max(0, event.time - open.startTime)
          state.llmMs += llmMs
          let ttftMs = null, decodeMs = null, decodeTokens = null
          if (open.firstTokenTime !== null) {
            ttftMs = Math.max(0, open.firstTokenTime - open.startTime)
            state.ttftMs += ttftMs
            state.ttftSteps += 1
            const outputTokens = typeof d.usage?.outputTokens === 'number'
              && Number.isFinite(d.usage.outputTokens) && d.usage.outputTokens >= 0
              ? d.usage.outputTokens : null
            if (outputTokens !== null) {
              decodeMs = Math.max(0, event.time - open.firstTokenTime)
              decodeTokens = outputTokens
              state.decodeMs += decodeMs
              state.decodeTokens += decodeTokens
            }
          }
          state.lastStep = { llmMs, ttftMs, decodeMs, decodeTokens }
          state.openStep = null
        }
        const u = usageTokensOf(d.usage)
        if (u !== null) {
          state.lastPressureTokens = u.input + u.cacheRead + u.cacheWrite
          state.inputTokens += u.input
          state.outputTokens += u.output
          state.cacheReadTokens += u.cacheRead
          state.cacheWriteTokens += u.cacheWrite
        }
        break
      }
      case 'tool/call':
        state.pendingCalls = { ...state.pendingCalls, [d.callId]: event.time }
        break
      case 'tool/result': {
        const callId = d.message?.source?.callId
        const dispatched = Object.hasOwn(state.pendingCalls, callId)
          ? state.pendingCalls[callId] : undefined
        if (dispatched !== undefined) {
          state.toolMs += Math.max(0, event.time - dispatched)
          const next = { ...state.pendingCalls }
          delete next[callId]
          state.pendingCalls = next
        }
        break
      }
      case 'step/end':
        state.turns = state.lastTurn === d.turn ? state.turns : state.turns + 1
        state.steps += 1
        state.lastTurn = d.turn
        state.openStep = null
        break
      case 'turn/end':
        if (Object.keys(state.pendingCalls).length > 0) state.pendingCalls = {}
        break
      case 'request/context':
        if (typeof d.contextWindow === 'number' && Number.isFinite(d.contextWindow) && d.contextWindow > 0) {
          state.contextWindow = d.contextWindow
        }
        break
      default:
        break
    }
  }
  const context = state.lastPressureTokens !== null && state.contextWindow !== null
    ? { usedTokens: state.lastPressureTokens, contextWindow: state.contextWindow }
    : null
  return {
    turns: state.turns, steps: state.steps,
    llmMs: state.llmMs, toolMs: state.toolMs,
    ttftMs: state.ttftMs, ttftSteps: state.ttftSteps,
    decodeMs: state.decodeMs, decodeTokens: state.decodeTokens,
    inputTokens: state.inputTokens, cacheReadTokens: state.cacheReadTokens,
    cacheWriteTokens: state.cacheWriteTokens, outputTokens: state.outputTokens,
    lastStep: state.lastStep,
    context,
  }
}
