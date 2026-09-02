/**
 * meow-jsonrpc —— 喵仓自定义 JSON-RPC 插件。
 *
 * 不 fork DSH 源码：复用官方导出的 HarnessSdkJsonRpcServer（标准方法
 * initialize / session/prompt / shutdown 与 session.event / session.status 通知）
 * 与 JsonRpcLineTransport（stdio 新行分隔 JSON-RPC 2.0 传输），通过子类扩展：
 *
 *   - session/cancel         {sessionId}            → agent.cancel({kind:'user'})（DSH 原生 API）
 *   - session/bash           {requestId,command,workdir?,timeoutMs?} → ctx.shell 后台执行，
 *                               期间以 session.bashOutput 通知流式转发增量，返回 exitCode 等
 *   - session/bashCancel     {requestId}            → 中止正在运行的终端命令
 *   - session/stats          {sessionId}            → 会话调用量统计（轮/步/时长/首token/tok/s/用量/最近一步/上下文）
 *   - session/prompt         +presetId/cwd 可选参数 → 会话创建时消费（Agent 预设 + 工作区归属，plan-standard-mode §三.4）
 *   - session/command        {sessionId,line,presetId?,cwd?} → commands.execute 程序化执行斜杠命令（§三.5）
 *   - session/query          {sessionId}            → 读持久化日志折叠 todo/plan/goal/preset（旧会话状态水合，§三.7）
 *   - session.question       通知（DSH→App）+ session/answerQuestion → 问答通道（§三.6），
 *                               provider 按连接生命周期注册/释放（UserQuestionService 单槽）
 *   - presets/list           {}                     → Agent 预设名单（自动扫描接口，§三.1）
 *   - presets/read           {id}                   → 读预设组合全文（§三.2）
 *   - presets/delete         {id}                   → 删用户预设（trust=user 才可删，§三.3）
 *   - personas/list          {}                     → 角色库名单（自动扫描 .agents/personas/，plan-memory-execution §1.7）
 *   - personas/reorder       {order:[id...]}        → 角色拖拽排序持久化（§1.8）
 *   - prompt 变量 soul/soul_path/user/user_path      → 向后兼容透传，取值一律来自会话快照
 *       （基座与官方预设不再引用，人格注入归 meow:memory section）
 *   - memory 工具（update/append/search）             → 按会话 memoryEnabled 注册到 agent 作用域
 *       （§1.9/§4.1），读写 .agents/memory/FACT.md 与 JOURNAL.jsonl
 *   - meow:memory section（order 50）                → 角色层 <soul>/<user> + 记忆层 <facts>/契约，
 *       按会话 personaEnabled/memoryEnabled 决定注入；内容首条消息固化快照
 *       （.agents/memory/snapshots/<sessionId>.json），保证 system prompt 前缀稳定可命中 KV 缓存
 *
 * 错误通道：预设/命令类错误以 MeowRpcError 抛出，经 MeowJsonRpcTransport 序列化为
 * 带 code（-32001..-32005）与 data 的 JSON-RPC error（官方 transport 只透传 message）。
 *
 * cordis.yml 中本插件取代官方 sdk-jsonrpc-server（两者都独占 stdin/stdout，
 * 不能共存）；其余能力由组合里的官方插件提供。
 */

import { createServer } from 'node:net'
import { randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync, renameSync, mkdirSync, readdirSync, openSync, writeSync, closeSync } from 'node:fs'
import { JsonRpcLineTransport } from '@deepseek-ai/dsh-sdk-protocol'
import { HarnessSdkJsonRpcServer } from '@deepseek-ai/dsh-sdk-jsonrpc-server'
import { SessionId } from '@deepseek-ai/dsh-session'
import { installModelSelection } from '@deepseek-ai/dsh-agent'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { isTokenDelta } from '@deepseek-ai/dsh-llm/message'
import { admitEncodedImages } from '@deepseek-ai/dsh-attachment'
import { resolveSessionPreset, UnknownPresetError, PresetMountError } from '@deepseek-ai/dsh-agent-presets'
import { UserQuestionError } from '@deepseek-ai/dsh-user-questions'
import z from '@deepseek-ai/schemastery'

export const name = 'meow-jsonrpc'

/** 需要 agents（cancel/官方 server 建会话）、shell（终端命令）与 systemPrompt（记忆 section 与兼容变量注册，apply 等它就绪才跑）三个服务 */
export const inject = ['agents', 'shell', 'systemPrompt']

/** 插件配置：全部可选，缺省值即喵仓部署形态 */
export const Config = z.object({
  /** max-token 结束的回合按成功上报（与官方部署一致：手机上宁可按成功展示） */
  maxTokensAsSuccess: z.boolean().default(true),
  /** session.bashOutput 流式转发的轮询间隔（毫秒） */
  bashStreamIntervalMs: z.number().default(80),
})

/** 终端输出流式转发的轮询间隔上限检查由调用处兜底；这里只做安全下限 */
const MIN_STREAM_INTERVAL_MS = 20

// ════════════════════════════════════════════════════════════════════════
// 记忆系统（plan-memory-execution，2026-09-02）
//
// 全局共享的常驻状态（跨 socket 连接复用，进程级生命周期；agent/disposed 清理）：
//   - memoryConfigs   sessionId → {personaId?, personaEnabled, memoryEnabled}（首条消息定死）
//   - memorySnapshots sessionId → 快照对象（soul/user/facts，首条消息固化，见 ensureSnapshot）
// 配置与快照内容分离：配置由 App 每次请求携带 + 首条消息写 Map；快照内容落
// .agents/memory/snapshots/<sessionId>.json（冷 resume 从文件恢复）。
// pendingMemoryConfigs 仍挂在 server 实例上（per-connection，仿 pendingHints）。
// ════════════════════════════════════════════════════════════════════════

/** 默认角色 id（personas/ 下含 persona.yml 的子目录；迁移存量 .agents/memory/SOUL.md 落此处） */
const DEFAULT_PERSONA_ID = 'default'

/** .agents 根（DSH_FILES_DIR 注入的是 filesDir，.agents 在其下） */
function agentsRoot() {
  return (process.env.DSH_FILES_DIR ?? process.cwd()) + '/.agents'
}

/** 记忆系统常驻配置 Map：sessionId → {personaId?, personaEnabled, memoryEnabled} */
const memoryConfigs = new Map()
/** 记忆系统快照缓存 Map：sessionId → 快照对象（与快照文件同构） */
const memorySnapshots = new Map()

/** 判断文本是否有实质内容（空 / 纯 HTML 注释 / 纯 markdown 注释行 → false） */
function hasSubstantiveContent(text) {
  const stripped = String(text ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('#'))
    .join(' ')
  return stripped.trim().length > 0
}

/**
 * 从 persona.yml 提取 name/description（极简 YAML 解析，足够本格式）：
 * 逐行跳过注释与空行，取 `key: value` 的 value（去引号）。坏 YAML 由调用方 try/catch 兜底。
 * @returns {{name: string, description: string}}
 */
function parsePersonaYaml(raw) {
  const result = { name: '', description: '' }
  for (const line of String(raw ?? '').split('\n')) {
    const trimmed = line.trim()
    if (trimmed.length === 0 || trimmed.startsWith('#')) continue
    const match = /^([A-Za-z_]+)\s*:\s*(.*)$/.exec(trimmed)
    if (match === null) continue
    const key = match[1]
    const value = match[2].trim().replace(/^["']|["']$/g, '')
    if (key === 'name' && result.name === '') result.name = value
    else if (key === 'description' && result.description === '') result.description = value
  }
  return result
}

/**
 * 角色回退链（design-memory-system §七）：
 * personaId 有效（personas/<id>/persona.yml 存在）→ 用之；
 * 空/未知/角色文件夹被删 → 默认角色（存在则用）→ 均缺 → null（空灵魂）。
 * @param {string|undefined} requested
 * @returns {string|null}
 */
function resolvePersonaId(requested) {
  const root = `${agentsRoot()}/personas`
  if (requested !== undefined && requested !== null && requested !== '') {
    try {
      if (readFileSync(`${root}/${requested}/persona.yml`, 'utf8')) return requested
    } catch {
      // 角色文件夹被删 / 无 persona.yml → 走回退
    }
  }
  try {
    readFileSync(`${root}/${DEFAULT_PERSONA_ID}/persona.yml`, 'utf8')
    return DEFAULT_PERSONA_ID
  } catch {
    return null
  }
}

/** 读某角色（或 null=无角色）的 SOUL.md / USER.md 原始内容；缺文件返回空串 */
function readPersonaFiles(personaId) {
  if (personaId === null || personaId === undefined || personaId === '') {
    return { soul: '', user: '' }
  }
  const root = `${agentsRoot()}/personas/${personaId}`
  let soul = ''
  let user = ''
  try { soul = readFileSync(`${root}/SOUL.md`, 'utf8') } catch {}
  try { user = readFileSync(`${root}/USER.md`, 'utf8') } catch {}
  return { soul, user }
}

/** 读全局共享 FACT.md（长期事实）；缺文件返回空串 */
function readFactsFile() {
  try {
    return readFileSync(`${agentsRoot()}/memory/FACT.md`, 'utf8')
  } catch {
    return ''
  }
}

/** 规范化为快照对象（容错：坏 JSON / 缺字段补默认） */
function normalizeSnapshot(parsed) {
  if (parsed === null || typeof parsed !== 'object') return null
  const persona = parsed.persona
  if (persona === null || typeof persona !== 'object') return null
  const memory = parsed.memory
  if (memory === null || typeof memory !== 'object') return null
  return {
    persona: {
      id: typeof persona.id === 'string' ? persona.id : null,
      soul: typeof persona.soul === 'string' ? persona.soul : '',
      user: typeof persona.user === 'string' ? persona.user : '',
    },
    memory: {
      facts: typeof memory.facts === 'string' ? memory.facts : '',
    },
    createdAt: typeof parsed.createdAt === 'string' ? parsed.createdAt : new Date().toISOString(),
  }
}

/**
 * 快照单点（plan-memory-execution §1.2，design-memory-system §快照持久化）：
 * 1. 内存 Map → 有则返回；
 * 2. 快照文件 `.agents/memory/snapshots/<sessionId>.json` → 载入 Map 后返回；
 * 3. 首条消息/极旧会话 → 读当前角色文件 + FACT.md 一次写齐快照 → 内存 Map → 返回。
 * 写快照失败不抛错（下次组装重试）；本函数同步（section provider 是同步签名）。
 * @param {string} sessionId
 * @param {boolean} [persist=true] 是否落盘。子代理（subagent）会话传 false：
 *   它们本就一次性存在、随父进程销毁，落盘只会让 snapshots/ 目录无限堆积；
 *   人格注入仍照常发生（与改造前基座 persona 对全 agent 生效的行为一致），
 *   只是这份快照活在内存里、进程重启即随会话一起没了。
 * @returns {object} 快照对象
 */
function ensureSnapshot(sessionId, persist = true) {
  if (memorySnapshots.has(sessionId)) return memorySnapshots.get(sessionId)
  const snapshotsDir = `${agentsRoot()}/memory/snapshots`
  const snapshotPath = `${snapshotsDir}/${sessionId}.json`
  // 2. 读快照文件（冷 resume / 进程重启后恢复）
  try {
    const normalized = normalizeSnapshot(JSON.parse(readFileSync(snapshotPath, 'utf8')))
    if (normalized !== null) {
      memorySnapshots.set(sessionId, normalized)
      return normalized
    }
  } catch {
    // 无快照 / 坏快照 → 走重建
  }
  // 3. 从当前角色文件 + FACT.md 构建
  const config = memoryConfigs.get(sessionId)
  const personaId = resolvePersonaId(config?.personaId)
  const { soul, user } = readPersonaFiles(personaId)
  const snapshot = {
    persona: { id: personaId, soul, user },
    memory: { facts: readFactsFile() },
    createdAt: new Date().toISOString(),
  }
  memorySnapshots.set(sessionId, snapshot)
  // 一次写齐快照文件（失败静默，下次组装重试）
  if (!persist) return snapshot
  try {
    mkdirSync(snapshotsDir, { recursive: true })
    const tmp = `${snapshotPath}.tmp-${process.pid}-${Date.now()}`
    writeFileSync(tmp, JSON.stringify(snapshot, null, 2))
    renameSync(tmp, snapshotPath)
  } catch {
    // 磁盘满 / EACCES：保持内存 Map，不打断 turn
  }
  return snapshot
}

/**
 * 角色编辑指引（persona ON 时注入，告知 AI 角色文件位置与快照语义）。
 * 用**绝对路径**：这些文件在 filesDir 下、不在会话工作区内，给相对路径会让模型按
 * cwd（files/workspace）去拼路径而读不到（真机实测踩过，喵~）。
 */
function roleEditingGuidance(personaId) {
  if (personaId === null || personaId === undefined || personaId === '') return ''
  return `你的角色设定文件在 ${agentsRoot()}/personas/${personaId}/ 下的 SOUL.md（人格）与 USER.md（用户档案），可用文件工具按该绝对路径编辑；改动只影响新会话——当前会话已在首条消息时固化快照。`
}

/**
 * 记忆存储契约文案（memory ON 时注入；design-memory-system §提示词存储契约）。
 * 同样一律给绝对路径，并明确「memory 工具本身不需要路径参数」，
 * 避免模型拿相对路径去 read/bash 探路而绕开工具。
 */
function memoryContractText() {
  const mem = `${agentsRoot()}/memory`
  return `【长期记忆】
记忆目录在 ${mem}（**不在会话工作区内**，要用绝对路径）。以下两个文件的路径都不必自己拼——memory 工具没有路径参数，直接用它：
- ${mem}/FACT.md：长期事实知识（跨会话，6 个月+）。用 memory 工具 action="update"、content=整份新内容 覆盖；也可用文件工具按上述绝对路径读写。
- ${mem}/JOURNAL.jsonl：一次性事件/会话笔记（append-only 日志）。只能用 memory 工具 append 追加、search 搜索；文件工具对这个路径会被拒绝，不要绕路去试。
- 记忆自主更新，不用问用户；写 FACT.md 前自问「6 个月后还有用吗？没用就 append 到日志」。
- 当前会话的 <facts> 已在首条消息时固化快照，你写入的新内容将在下一个新会话的 <facts> 快照中体现（所以本会话内别指望重读能看到新内容，也不必重读确认）。
- ${mem}/snapshots/ 是内部快照缓存目录，禁止读取/修改。`
}

/**
 * 组装记忆 section 文本（plan-memory-execution §1.3）：
 * 按开关决定注入：
 *   personaEnabled ON → <soul> + <user> + 角色编辑指引（内容有实质文字才注入对应标签）
 *   memoryEnabled ON   → <facts> + 存储契约
 * 两开关均 OFF → 返回空串（renderPrompt 会丢弃空 section）。
 * @param {object} context - assemble 的 AssembleContext（含 agent.id = sessionId）
 */
function memorySectionText(context) {
  const sessionId = context?.agent?.id
  if (sessionId === undefined) return ''
  const key = String(sessionId)
  // 子代理会话（session.header.parentSession 有值）只在内存里固化，不落盘（防目录膨胀）
  const isChildSession = context?.agent?.session?.header?.parentSession !== undefined
  const snapshot = ensureSnapshot(key, !isChildSession)
  const config = memoryConfigs.get(key) ?? {}
  const personaEnabled = config.personaEnabled !== false
  const memoryEnabled = config.memoryEnabled !== false
  const parts = []
  if (personaEnabled) {
    const soul = snapshot.persona?.soul ?? ''
    const user = snapshot.persona?.user ?? ''
    const hasSoul = hasSubstantiveContent(soul)
    const hasUser = hasSubstantiveContent(user)
    if (hasSoul || hasUser) {
      const roleParts = []
      if (hasSoul) roleParts.push(`<soul>\n${soul.trim()}\n</soul>`)
      if (hasUser) roleParts.push(`<user>\n${user.trim()}\n</user>`)
      const guidance = roleEditingGuidance(snapshot.persona?.id)
      if (guidance !== '') roleParts.push(guidance)
      parts.push(roleParts.join('\n\n'))
    }
  }
  if (memoryEnabled) {
    const facts = snapshot.memory?.facts ?? ''
    if (hasSubstantiveContent(facts)) {
      parts.push(`<facts>\n${facts.trim()}\n</facts>`)
    }
    parts.push(memoryContractText())
  }
  return parts.join('\n\n')
}

/**

 * 把思考强度钳制到目标模型的能力范围内（防止 DeepSeek 的 'high' 被原样带到
 * 不支持思考的 OpenAI 兼容模型上，导致请求以 UNSUPPORTED_REASONING_EFFORT 失败）。
 *
 * 关键：核心 llm 服务（resolveCallFor）对「无思考能力」的模型（reasoning 元数据
 * 缺失，如自定义 provider 的模型）会拒绝**任何**显式强度——包括 'off'。所以：
 *   - 模型支持该强度 → 原样保留；
 *   - 模型无思考能力或不支持该强度 → **不传**（undefined，交 provider 默认/不思考），
 *     而不是退回 'off'（那同样会被核心校验拒绝）；
 *   - 模型支持思考且有无默认强度 → 用模型默认（前提是默认也在支持列表里）。
 * 查询失败（provider 未注册/模型不存在等）→ 原样保留，把真实错误留给上游，不掩盖。
 * @param llm - llm 服务（可能 undefined）
 * @param provider - 目标 provider 路由
 * @param model - 目标模型 id
 * @param effort - 期望思考强度（undefined = 不指定，交给 provider 默认）
 * @returns {{ effort?: string, modelReasoning?: { efforts: string[], defaultEffort?: string } }}
 */
async function clampReasoningEffort(llm, provider, model, effort) {
  if (effort === undefined) return { effort: undefined, modelReasoning: undefined }
  let info
  try {
    if (llm === undefined) return { effort, modelReasoning: undefined }
    info = await llm.resolveModelInfo(provider, model)
  } catch {
    return { effort, modelReasoning: undefined }
  }
  const reasoning = info?.reasoning
  if (reasoning === undefined) {
    // 模型无思考能力：任何显式强度（含 off）都会被核心校验拒绝 → 不传
    return { effort: undefined, modelReasoning: undefined }
  }
  const efforts = reasoning.efforts.map((entry) => String(entry.id))
  const modelReasoning = {
    efforts,
    ...(reasoning.defaultEffort === undefined ? {} : { defaultEffort: String(reasoning.defaultEffort) }),
  }
  if (efforts.includes(effort)) return { effort, modelReasoning }
  // 不支持当前强度 → 模型默认强度（且必须在支持列表里），否则不传
  const fallback = reasoning.defaultEffort === undefined ? undefined : String(reasoning.defaultEffort)
  if (fallback !== undefined && efforts.includes(fallback)) return { effort: fallback, modelReasoning }
  return { effort: undefined, modelReasoning }
}

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
function foldSessionStats(events) {
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

/**
 * 结构化 RPC 错误（plan-standard-mode §三.8 错误映射约定）。
 *
 * 官方 JsonRpcLineTransport 对 handler 抛错只回 `-32603 + message`；预设/命令类
 * 错误需要把稳定 code 与结构化 data（可用预设列表、挂载失败逐行原因等）送到 App，
 * 所以本插件用 MeowJsonRpcTransport（下方子类）识别 MeowRpcError 并序列化
 * `error.code`（-32001..-32005 服务器自定义区段）与 `error.data`。
 */
const RPC_ERROR = {
  /** 请求的 Agent 预设不存在；data.available = 名单里实际可用的 id 列表 */
  PRESET_UNKNOWN: -32001,
  /** 预设存在但组合挂载失败；data.detail = 逐行原因（PresetMountError.reason） */
  PRESET_MOUNT_FAILED: -32002,
  /** commands 服务未挂载（斜杠命令通道不可用） */
  COMMAND_UNAVAILABLE: -32003,
  /** 命令行解析不到已注册命令（含旧会话未 join 预设、没有 /plan 的场景） */
  COMMAND_UNKNOWN: -32004,
  /** 内置（trust=system）预设不可删除 */
  PRESET_IMMUTABLE: -32005,
}

/** 构造一个带稳定 code/data 的 RPC 错误；由 MeowJsonRpcTransport 识别并结构化回传 */
function meowRpcError(code, message, data) {
  const error = new Error(message)
  error.meowRpc = true
  error.rpcCode = code
  error.rpcData = data
  return error
}

/**
 * 把 agent-presets 域的异常映射为结构化 RPC 错误；非预设异常原样重抛。
 * UnknownPresetError.message 已含 available 列表，data 里再给结构化一份。
 * @returns {never} 总是以 throw 结束
 */
function throwMappedPresetError(error) {
  if (error instanceof UnknownPresetError) {
    throw meowRpcError(RPC_ERROR.PRESET_UNKNOWN, error.message, {
      code: 'PRESET_UNKNOWN',
      presetId: error.presetId,
      available: [...error.available],
    })
  }
  if (error instanceof PresetMountError) {
    throw meowRpcError(RPC_ERROR.PRESET_MOUNT_FAILED, error.message, {
      code: 'PRESET_MOUNT_FAILED',
      presetId: error.presetId,
      detail: error.reason,
    })
  }
  throw error
}

/**
 * 喵仓版行传输：父类的 handler 异常路径只写 `-32603 + message`，这里识别
 * MeowRpcError（`meowRpc === true` 标记）改写为带稳定 code 与 data 的 error 帧，
 * 其余行为与父类完全一致。requestHandler/write/writeError 在 TS 里是 private，
 * 运行时是普通属性/原型方法，JS 子类照常访问。
 */
class MeowJsonRpcTransport extends JsonRpcLineTransport {
  async handleIncomingRequest(id, method, params) {
    const handler = this.requestHandler
    if (handler === undefined) {
      this.writeError(id, -32601, `method not found: ${method}`)
      return
    }
    try {
      const result = await handler(method, params)
      this.write({ jsonrpc: '2.0', id, result })
    } catch (error) {
      if (error !== null && typeof error === 'object' && error.meowRpc === true) {
        this.write({
          jsonrpc: '2.0',
          id,
          error: {
            code: error.rpcCode,
            message: error.message,
            ...(error.rpcData !== undefined ? { data: error.rpcData } : {}),
          },
        })
      } else {
        this.writeError(id, -32603, error instanceof Error ? error.message : String(error))
      }
    }
  }
}

/**
 * 喵仓版 SDK server：官方标准方法全部走 super，仅扩展自定义方法。
 */
class MeowJsonRpcServer extends HarnessSdkJsonRpcServer {
  /** @param ctx 启动后的 Cordis 上下文（提供 agents / shell 服务） */
  constructor(ctx, transport, options = {}) {
    super(ctx, transport, options)
    this.ctx = ctx
    this.transport = transport
    this.streamIntervalMs = Math.max(MIN_STREAM_INTERVAL_MS, options.streamIntervalMs ?? 80)
    /** requestId → 正在运行的终端命令句柄 */
    this.runningBash = new Map()
    /** sessionId → 进行中的 resume（与官方 sessionCreations 同理的去重） */
    this.resumeCreations = new Map()
    /** sessionId → 运行时模型选择（provider/model/reasoningEffort 可变，installModelSelection 用） */
    this.selections = new Map()
    /** initialize 时设置的思考强度（off/high/max），作为新会话默认 */
    this.reasoningEffort = undefined
    /** sessionId → {presetId?, cwd?}：session/prompt、session/command 透传的会话创建提示（§三.4） */
    this.pendingHints = new Map()
    /** sessionId → {personaId?, personaEnabled?, memoryEnabled?}：待写入常驻 Map 的记忆配置（plan-memory-execution §1.1，per-connection） */
    this.pendingMemoryConfigs = new Map()
    /** requestId → {resolve,reject,cleanup}：等待 App 回答的 userQuestions 请求（§三.6） */
    this.pendingQuestions = new Map()
    /** 本连接注册的问答 provider 释放器（UserQuestionService 全局单槽，连接结束必须释放） */
    this.questionProviderDisposer = undefined
    // 会话销毁时清理本连接持有的记忆配置暂存（常驻 Map 的清理由 apply() 里的 agent/disposed 监听负责）
    this.disposers?.push?.(ctx.on('agent/disposed', ({ agent }) => {
      this.pendingMemoryConfigs.delete(String(agent.id))
    }))
  }

  /**
   * agent-presets 名单服务（键名驼峰 `agentPresets`）。
   *
   * 惰性取而非构造时缓存：插件 apply 时机早于 agent-presets 自己的 fiber 启动
   * （cordis 无严格启动顺序），构造时 get 可能拿到 undefined；RPC 调用都发生在
   * 启动完成之后，调用时取是稳定值。未挂载返回 undefined —— 所有预设相关方法
   * 按计划降级为旧行为（§4.4①）。
   */
  presetsService() {
    return this.ctx.get('agentPresets')
  }

  /**
   * 把 session/prompt、session/command 参数里的 presetId/cwd 写进 pendingHints。
   * 只在至少一个字段非空时写入；getOrCreateSession 消费后兜底删除（§4.4②）。
   */
  stashHints(sessionId, params) {
    const presetId = params?.presetId
    const cwd = params?.cwd
    const hasPreset = presetId !== undefined && presetId !== null && presetId !== ''
    const hasCwd = cwd !== undefined && cwd !== null && cwd !== ''
    if (!hasPreset && !hasCwd) return
    this.pendingHints.set(String(sessionId), {
      ...(hasPreset ? { presetId: String(presetId) } : {}),
      ...(hasCwd ? { cwd: String(cwd) } : {}),
    })
  }

  /**
   * 会话已存在而请求仍带 presetId/cwd 时的 warn 对照（§三.4③：以日志为唯一事实源，
   * 参数忽略只记 warn 不报错）。纯内部诊断，不向调用方暴露。
   */
  warnHintsIgnored(sessionId, hints) {
    if (hints === undefined) return
    try {
      const agent = this.sessions.get(sessionId)?.handle?.agent
      if (agent === undefined) return
      const recordedPreset = this.presetsService()?.composedPreset?.(agent.ctx)
      if (hints.presetId !== undefined && recordedPreset !== undefined && recordedPreset !== hints.presetId) {
        this.ctx.logger?.warn?.(
          `meow-jsonrpc: session ${sessionId} runs preset "${recordedPreset}", ignoring requested "${hints.presetId}"`,
        )
      }
      const recordedCwd = agent.session?.header?.cwd
      if (hints.cwd !== undefined && recordedCwd !== undefined && recordedCwd !== hints.cwd) {
        this.ctx.logger?.warn?.(
          `meow-jsonrpc: session ${sessionId} runs cwd "${recordedCwd}", ignoring requested "${hints.cwd}"`,
        )
      }
    } catch {
      // 对照诊断本身不允许影响会话获取
    }
  }

  /**
   * 记忆配置暂存（plan-memory-execution §1.6）：把 session/prompt 参数里的
   * personaId/personaEnabled/memoryEnabled 写入 pendingMemoryConfigs，供 makeSetup 消费定死。
   * - 已在内存的 live 会话（首条消息已过）：参数仅与常驻 Map 对照，不符 warn 忽略；
   * - 新会话 / 冷 resume（进程重启后无 live session）：暂存，随 getOrCreateSession 消费。
   */
  stashMemoryConfig(sessionId, params) {
    const key = String(sessionId)
    const hasPersonaId = params?.personaId !== undefined && params.personaId !== null && params.personaId !== ''
    const hasPersonaEnabled = typeof params?.personaEnabled === 'boolean'
    const hasMemoryEnabled = typeof params?.memoryEnabled === 'boolean'
    if (!hasPersonaId && !hasPersonaEnabled && !hasMemoryEnabled) return
    if (this.sessions.has(key)) {
      const existing = memoryConfigs.get(key)
      if (existing === undefined) {
        // live 会话但无常驻配置（例如先经 session/command 建会话再发首条消息）：
        // 仍视为「首条消息」直接定死，否则 personaId 会永久丢失。
        memoryConfigs.set(key, {
          ...(hasPersonaId ? { personaId: String(params.personaId) } : {}),
          personaEnabled: params.personaEnabled ?? true,
          memoryEnabled: params.memoryEnabled ?? true,
        })
        return
      }
      // 首条消息已过：对照常驻 Map，不符 warn 忽略（首条定死）
      if (hasPersonaId && existing.personaId !== String(params.personaId)) {
        this.ctx.logger?.warn?.(`meow-jsonrpc: session ${key} locked personaId "${existing.personaId}", ignoring "${params.personaId}"`)
      }
      if (hasPersonaEnabled && existing.personaEnabled !== params.personaEnabled) {
        this.ctx.logger?.warn?.(`meow-jsonrpc: session ${key} locked personaEnabled=${existing.personaEnabled}, ignoring ${params.personaEnabled}`)
      }
      if (hasMemoryEnabled && existing.memoryEnabled !== params.memoryEnabled) {
        this.ctx.logger?.warn?.(`meow-jsonrpc: session ${key} locked memoryEnabled=${existing.memoryEnabled}, ignoring ${params.memoryEnabled}`)
      }
      return
    }
    this.pendingMemoryConfigs.set(key, {
      ...(hasPersonaId ? { personaId: String(params.personaId) } : {}),
      ...(hasPersonaEnabled ? { personaEnabled: params.personaEnabled } : {}),
      ...(hasMemoryEnabled ? { memoryEnabled: params.memoryEnabled } : {}),
    })
  }

  /**
   * 把一条记忆配置写进常驻 Map（首条定死）。
   *
   * ⚠️ 取值来源必须是**调用方同步捕获的快照**，不能在这里回查 pendingMemoryConfigs：
   * getOrCreateSession 返回的是未 await 的 creation promise，它的 finally 会在
   * 函数 return 的当下就把暂存删干净，而 makeSetup 是在那之后的异步体里才跑的
   * （pendingHints 正是靠开头同步取值 + 闭包捕获才没踩到这个坑，喵~）。
   * 合并语义：缺失字段保留已有值，整体缺省回退默认（两开关默认 true）。
   */
  applyMemoryConfig(sessionId, pending) {
    if (pending === undefined) return
    const key = String(sessionId)
    const existing = memoryConfigs.get(key)
    memoryConfigs.set(key, {
      ...(pending.personaId !== undefined
        ? { personaId: pending.personaId }
        : existing?.personaId !== undefined ? { personaId: existing.personaId } : {}),
      personaEnabled: pending.personaEnabled ?? existing?.personaEnabled ?? true,
      memoryEnabled: pending.memoryEnabled ?? existing?.memoryEnabled ?? true,
    })
  }

  /**
   * 记忆工具注册（plan-memory-execution §4.1，per-agent scope，仿 tool-ask-user 写法）。
   * memoryEnabled OFF 时不调用（makeSetup 判断）。update 原子覆盖 FACT.md；
   * append O_APPEND 追加 JOURNAL.jsonl；search 子串+tag+limit，坏行 skip 而非 throw。
   */
  installMemoryTool(agentCtx) {
    const memoryDir = `${agentsRoot()}/memory`
    const factsPath = `${memoryDir}/FACT.md`
    const journalPath = `${memoryDir}/JOURNAL.jsonl`
    agentCtx.tools.register(defineTool({
      name: 'memory',
      description: '读写长期记忆：update 整份覆盖 FACT.md、append 追加一条到 JOURNAL 日志、search 检索日志。'
        + '本工具不需要任何文件路径参数，直接按 action 调用即可（记忆文件不在会话工作区内，别用 read/bash 去探路）。',
      parameters: {
        action: {
          type: 'string',
          enum: ['update', 'append', 'search'],
          required: true,
          description: 'What to do: update FACT.md, append a journal entry, or search the journal.',
        },
        content: { type: 'string', description: 'For update: the full new content of FACT.md (atomic overwrite).' },
        text: { type: 'string', description: 'For append: the journal entry text.' },
        tags: { type: 'array', items: { type: 'string' }, description: 'For append: optional tags.' },
        query: { type: 'string', description: 'For search: case-insensitive substring to match.' },
        tag: { type: 'string', description: 'For search: filter entries that carry this tag.' },
        limit: { type: 'integer', description: 'For search: max entries to return (default 20).' },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            ok: { type: 'boolean', required: true },
            message: { type: 'string' },
            matches: {
              type: 'array',
              items: {
                type: 'object',
                additionalProperties: false,
                properties: {
                  ts: { type: 'string' },
                  tags: { type: 'array', items: { type: 'string' } },
                  text: { type: 'string' },
                },
              },
            },
          },
        },
        render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }],
      },
      async execute(args, _exec) {
        switch (args.action) {
          case 'update': {
            if (typeof args.content !== 'string') throw new Error('memory: content is required for update')
            mkdirSync(memoryDir, { recursive: true })
            const tmp = `${factsPath}.tmp-${process.pid}-${Date.now()}`
            writeFileSync(tmp, args.content)
            renameSync(tmp, factsPath)
            return {
              ok: true,
              message: 'FACT.md 已更新（下个新会话的 <facts> 快照会包含新内容；当前会话快照不变）',
            }
          }
          case 'append': {
            if (typeof args.text !== 'string' || args.text.length === 0) {
              throw new Error('memory: text is required for append')
            }
            mkdirSync(memoryDir, { recursive: true })
            const tags = Array.isArray(args.tags) ? args.tags.map(String) : []
            const entry = { ts: new Date().toISOString(), tags, text: args.text }
            const fd = openSync(journalPath, 'a')
            try {
              writeSync(fd, JSON.stringify(entry) + '\n')
            } finally {
              closeSync(fd)
            }
            return { ok: true, message: '已追加到 JOURNAL.jsonl' }
          }
          case 'search': {
            const query = typeof args.query === 'string' ? args.query.toLowerCase() : ''
            const tag = typeof args.tag === 'string' ? args.tag : undefined
            const limit = Number.isInteger(args.limit) && args.limit > 0 ? Math.min(args.limit, 200) : 20
            const matches = []
            try {
              const content = readFileSync(journalPath, 'utf8')
              for (const line of content.split('\n')) {
                if (line.trim() === '') continue
                let entry
                try {
                  entry = JSON.parse(line)
                } catch {
                  continue // 坏行容忍：历史坏数据不使 search 失败
                }
                const text = typeof entry.text === 'string' ? entry.text : ''
                const entryTags = Array.isArray(entry.tags) ? entry.tags.map(String) : []
                if (query !== '' && !text.toLowerCase().includes(query)) continue
                if (tag !== undefined && !entryTags.includes(tag)) continue
                matches.push({
                  ts: typeof entry.ts === 'string' ? entry.ts : '',
                  tags: entryTags,
                  text,
                })
                if (matches.length >= limit) break
              }
            } catch (error) {
              if (error?.code !== 'ENOENT') throw error // 日志不存在 = 无记录
            }
            return { ok: true, matches }
          }
          default:
            throw new Error(`memory: unknown action "${String(args.action)}"`)
        }
      },
    }))
  }

  /**
   * personas/list（plan-memory-execution §1.7）：自动扫描 .agents/personas/ 子目录，
   * 仅含 persona.yml 的目录算角色；读 name/description（坏 YAML try/catch 跳过）；
   * 按 .personas-order 排序，未列出角色按字母序排最后；isDefault = id === 'default'。
   */
  async personasList() {
    const root = `${agentsRoot()}/personas`
    let entries
    try {
      entries = readdirSync(root, { withFileTypes: true })
    } catch {
      return { personas: [] }
    }
    const personas = []
    for (const entry of entries) {
      if (!entry.isDirectory()) continue
      const id = entry.name
      // 排除规则（§1.7）：仅「含 persona.yml 的子目录」算角色 —— README.md/skills/ 等自动落榜
      let raw
      try {
        raw = readFileSync(`${root}/${id}/persona.yml`, 'utf8')
      } catch {
        continue
      }
      let name = id
      let description = ''
      try {
        const parsed = parsePersonaYaml(raw)
        if (parsed.name !== '') name = parsed.name
        description = parsed.description
      } catch {
        // 坏 YAML → 按 id 兜底保留条目（用户仍能在选择器里看到并修/删），不使整表失败
      }
      personas.push({ id, name, description, isDefault: id === DEFAULT_PERSONA_ID })
    }
    let order = []
    try {
      const raw = JSON.parse(readFileSync(`${root}/.personas-order`, 'utf8'))
      if (Array.isArray(raw)) order = [...new Set(raw.map(String))]
    } catch {
      // 无排序文件 → 全部按字母序
    }
    const byId = new Map(personas.map((p) => [p.id, p]))
    const ordered = order.map((id) => byId.get(id)).filter((p) => p !== undefined)
    const listed = new Set(order)
    const unordered = personas
      .filter((p) => !listed.has(p.id))
      .sort((a, b) => a.id.localeCompare(b.id))
    return { personas: [...ordered, ...unordered] }
  }

  /**
   * personas/reorder（plan-memory-execution §1.8）：前端拖拽排序持久化。
   * 过滤掉不存在的角色 id（与 personasList 扫描结果对拍），幂等去重后写 .personas-order。
   */
  async personasReorder(params) {
    const requested = Array.isArray(params?.order) ? params.order.map(String) : []
    const valid = new Set((await this.personasList()).personas.map((p) => p.id))
    const filtered = [...new Set(requested.filter((id) => valid.has(id)))]
    const root = `${agentsRoot()}/personas`
    try {
      mkdirSync(root, { recursive: true })
      writeFileSync(`${root}/.personas-order`, JSON.stringify(filtered))
    } catch (error) {
      throw new Error(`personas/reorder failed: ${error instanceof Error ? error.message : String(error)}`)
    }
    return { order: filtered }
  }

  /**
   * 从持久化日志解析会话所属预设 id（resolveSessionPreset 语义：header 是创建时值，
   * 空白期切换过预设的会话以最后一条 agent-preset/selected 事件为准）。
   * @returns {string | undefined} 无名单/无持久化/读日志失败时返回 undefined
   */
  async loggedPresetId(sessionId) {
    if (this.presetsService() === undefined) return undefined
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) return undefined
    try {
      const inspection = await persistence.load(SessionId(sessionId))
      return resolveSessionPreset({ header: inspection.meta, events: inspection.events })
    } catch {
      return undefined
    }
  }

  /** 覆盖 initialize：额外接收 reasoningEffort（off/high/max）作为新会话默认 */
  async initialize(params) {
    const result = await super.initialize(params)
    if (params?.reasoningEffort !== undefined) this.reasoningEffort = params.reasoningEffort
    return result
  }

  /** 构建 agentOptions（provider/model/maxTokens；reasoningEffort 经 installModelSelection 传） */
  agentOptionsFor() {
    return {
      ...(this.provider === undefined ? {} : { provider: this.provider }),
      ...(this.model === undefined ? {} : { model: this.model }),
      ...(this.maxTokens === undefined ? {} : { maxTokens: this.maxTokens }),
    }
  }

  /** 当前默认模型选择（provider/model/reasoningEffort），新会话与 setModel 的初始值 */
  currentSelection() {
    return {
      provider: this.provider,
      model: this.model,
      ...(this.reasoningEffort === undefined ? {} : { reasoningEffort: this.reasoningEffort }),
    }
  }

  /**
   * 覆盖官方（TS private）会话获取：统一 create/resume，并在 setup 里安装
   * installModelSelection（运行时切换 provider/model/reasoningEffort）与
   * Agent 预设挂载（plan-standard-mode §4.4①，照官方 api-proxy composeAgent 配方），
   * 同 sessionId 复用 live agent + selection。磁盘已有持久化日志时走 resume。
   * TS 的 private 只是编译期约束，运行时属性照常存在；本插件锁定 DSH rc.2。
   *
   * 预设/工作区语义（§三.4，App 把归属缓冲在 Room 行、随首条消息携带）：
   *   - create 路径：消费 pendingHints —— resolve（未知 → PRESET_UNKNOWN；不传 → 默认预设）
   *     在 create 之前完成，因为会话边界在异步 setup 开始前快照 meta；挂载在 setup 里做，
   *     失败整体回滚创建（官方配方同款时序）；cwd 未传回落全局 DSH_CWD。
   *   - resume 路径：hints 忽略（日志为唯一事实源），但必须按日志重挂预设 —— standing
   *     mount 随进程消失，冷 resume 只恢复日志不恢复进程内挂载（官方原话 "Cold resume
   *     composes the preset the session recorded"）；日志无预设记录（升级前旧会话）→
   *     不挂载，行为同旧。
   *   - 兜底：无论走哪条路径，末尾删除 pendingHints —— 会话已存在时 hints 不被消费，
   *     不清理会残留（§4.4②）。
   */
  async getOrCreateSession(sessionId) {
    if (this.shuttingDown) throw new Error('SDK server is shutting down')
    const hints = this.pendingHints.get(sessionId)
    // 同步捕获（同上：finally 会在本函数 return 的当下清掉暂存）
    const memoryHints = this.pendingMemoryConfigs.get(sessionId)
    try {
      if (this.sessions.has(sessionId)) {
        this.warnHintsIgnored(sessionId, hints)
        // live 但无常驻配置（另一连接刚建过会话）：补写，保证 personaId 不丢
        this.applyMemoryConfig(sessionId, memoryHints)
        return this.sessions.get(sessionId)
      }
      const pending = this.resumeCreations.get(sessionId)
      if (pending) return pending
      const presets = this.presetsService()
      const persistence = this.ctx.get('sessionPersistence')
      const creation = (async () => {
        // 会话初始选择：当前默认 (provider/model/reasoningEffort)，
        // 思考强度按目标模型能力钳制（见 clampReasoningEffort，避免全局 high 带崩不支持思考的模型）
        const current = this.currentSelection()
        const clamped = await clampReasoningEffort(this.ctx.get('llm'), current.provider, current.model, current.reasoningEffort)
        const selection = {
          current: {
            provider: current.provider,
            model: current.model,
            ...(clamped.effort === undefined ? {} : { reasoningEffort: clamped.effort }),
          },
          assembled: undefined,
        }
        /** setup 工厂：两种路径共用模型选择安装，预设 id 不同（resume=日志值，create=hints 值） */
        const makeSetup = (presetId) => async (agentCtx) => {
          installModelSelection(agentCtx, selection)
          this.selections.set(sessionId, selection)
          // 首条消息定死记忆配置（plan-memory-execution §1.1/§1.6）：makeSetup 早于任何
          // assemble，先把开头同步捕获的快照写进常驻 Map，section provider 与工具注册都据它判定。
          this.applyMemoryConfig(sessionId, memoryHints)
          if (presets !== undefined && presetId !== undefined) {
            try {
              await presets.mount(agentCtx, presetId)
            } catch (error) {
              throwMappedPresetError(error)
            }
          }
          // 记忆工具按开关条件注册到 agent 作用域（§1.9）：OFF → 完全不注册，AI 不知情。
          // 注册在 mount 之后、agentCtx 自己的层 —— tools.restrict 只裁继承面，不裁本层
          // （meow-minimal 的 minimal-face 不影响记忆工具，喵~）。
          if (memoryConfigs.get(sessionId)?.memoryEnabled !== false) {
            this.installMemoryTool(agentCtx)
          }
        }
        let handle
        if (persistence !== undefined) {
          try {
            // 冷 resume：按日志重挂预设（漏了这句重启后所有预设会话裸奔）
            const loggedPresetId = await this.loggedPresetId(sessionId)
            handle = await this.ctx.agents.resume({
              resumeSessionId: SessionId(sessionId),
              agentOptions: this.agentOptionsFor(),
              setup: makeSetup(loggedPresetId),
            })
          } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            if (!message.includes('not found')) {
              // 并发竞态：别的调用方刚把该会话 resume 成 live → 复用 live agent
              const live = this.ctx.agents.get(SessionId(sessionId))
              if (live !== undefined) {
                const rec = { handle: { agent: live, dispose: async () => {} } }
                this.sessions.set(sessionId, rec)
                return rec
              }
              throw error
            }
            // 磁盘无此会话 → 全新创建。预设解析在 create 之前（meta 快照时序），失败
            // 时会话未创建、错误原样抛给 prompt → PRESET_UNKNOWN / PRESET_MOUNT_FAILED
            let resolvedPreset
            if (presets !== undefined) {
              try {
                resolvedPreset = await presets.resolve(hints?.presetId)
              } catch (error) {
                throwMappedPresetError(error)
              }
            }
            handle = await this.ctx.agents.create({
              sessionId: SessionId(sessionId),
              meta: {
                cwd: hints?.cwd !== undefined ? hints.cwd : this.cwd,
                ...(resolvedPreset !== undefined ? { agentPreset: resolvedPreset.id } : {}),
              },
              agentOptions: this.agentOptionsFor(),
              setup: makeSetup(resolvedPreset?.id),
            })
          }
        } else {
          let resolvedPreset
          if (presets !== undefined) {
            try {
              resolvedPreset = await presets.resolve(hints?.presetId)
            } catch (error) {
              throwMappedPresetError(error)
            }
          }
          handle = await this.ctx.agents.create({
            sessionId: SessionId(sessionId),
            meta: {
              cwd: hints?.cwd !== undefined ? hints.cwd : this.cwd,
              ...(resolvedPreset !== undefined ? { agentPreset: resolvedPreset.id } : {}),
            },
            agentOptions: this.agentOptionsFor(),
            setup: makeSetup(resolvedPreset?.id),
          })
        }
        const rec = { handle }
        this.sessions.set(sessionId, rec)
        return rec
      })()
      this.resumeCreations.set(sessionId, creation)
      void creation.then(
        () => { this.resumeCreations.delete(sessionId) },
        () => { this.resumeCreations.delete(sessionId) },
      )
      return creation
    } finally {
      this.pendingHints.delete(sessionId)
      // 暂存未被消费（live 快路径 / 并发竞态复用 live agent）时兜底清理，避免残留
      this.pendingMemoryConfigs.delete(sessionId)
    }
  }

  /**
   * 覆盖官方 prompt：把参数里的 presetId/cwd 写进 pendingHints、personaId/开关写进
   * pendingMemoryConfigs（§三.4 / plan-memory-execution §1.6）后走官方流程
   * （super.prompt → this.getOrCreateSession 虚分发到上方覆盖版，创建时消费）。
   * 预设域异常映射为结构化 RPC 错误（§三.8）。
   */
  async prompt(params) {
    const sessionId = String(params?.sessionId ?? '')
    this.stashHints(sessionId, params)
    this.stashMemoryConfig(sessionId, params)
    try {
      return await super.prompt(params)
    } catch (error) {
      throwMappedPresetError(error)
    }
  }

  /** 运行时切换某会话的模型/思考强度（影响该会话下一次请求；未建 live agent 或空 sessionId 时更新全局默认） */
  async setModel(params) {
    // sessionId 为空 = 只更新运行时全局默认（无打开的会话时切模型/强度），不绑定某个会话
    const sessionId = params.sessionId === undefined || params.sessionId === null ? '' : String(params.sessionId)
    const ref = this.selections.get(sessionId)
    const base = ref?.current ?? this.currentSelection()
    // 目标 (provider/model) 与期望强度：只更新调用方传入的字段
    const provider = params.provider !== undefined ? String(params.provider) : base.provider
    const model = params.model !== undefined ? String(params.model) : base.model
    const target = params.reasoningEffort !== undefined ? String(params.reasoningEffort) : base.reasoningEffort
    // 思考强度钳制到目标模型能力内：切换 provider/模型时不再把上一任模型的
    // 强度原样带过去（DeepSeek high → 硅基流动千问会 UNSUPPORTED_REASONING_EFFORT）
    const clamped = await clampReasoningEffort(this.ctx.get('llm'), provider, model, target)
    const next = {
      provider,
      model,
      ...(clamped.effort === undefined ? {} : { reasoningEffort: clamped.effort }),
    }
    if (ref !== undefined) ref.current = next
    // 运行时默认同步更新：setModel 一次即完成全局切模型，后续新会话（create/resume）继承同样的选择
    this.provider = provider
    this.model = model
    if (clamped.effort !== undefined) this.reasoningEffort = clamped.effort
    return {
      sessionId,
      selection: next,
      ...(clamped.modelReasoning === undefined ? {} : { modelReasoning: clamped.modelReasoning }),
    }
  }

  /** provider 名 → credential ref（POSIX 标识符；provider 名非字母数字转下划线） */
  providerCredentialRef(provider) {
    return 'MEOW_' + String(provider).replace(/[^A-Za-z0-9]/g, '_').toUpperCase() + '_API_KEY'
  }

  /** llm/providers：可配置 provider 目录 + 已注册状态合并 */
  listProviders() {
    const llm = this.ctx.get('llm')
    if (llm === undefined) return { providers: [] }
    const registered = new Set(llm.listProviders().map((p) => p.id))
    const providers = llm.listConfigurableProviders().map((entry) => ({
      provider: entry.provider,
      displayName: entry.displayName,
      settingsNs: entry.settingsNs,
      settingsPath: entry.settingsPath,
      registered: registered.has(entry.provider),
    }))
    return { providers }
  }

  /** llm/models：某 provider 的模型目录（逐模型附 reasoning 元数据，供聊天页动态渲染思考档位） */
  async listModels(params) {
    const provider = String(params.provider ?? '')
    if (provider === '') throw new Error('llm/models: provider is required')
    const llm = this.ctx.get('llm')
    if (llm === undefined) throw new Error('llm service unavailable')
    const models = await llm.listModels(provider)
    return {
      models: await Promise.all(models.map(async (m) => {
        // resolveModelInfo 是适配器本地查表；个别模型解析失败（目录与路由不同步等）只丢 reasoning 不影响条目
        let reasoning
        try {
          const resolved = await llm.resolveModelInfo(provider, m.id)
          if (resolved?.reasoning !== undefined) {
            reasoning = {
              efforts: resolved.reasoning.efforts.map((entry) => String(entry.id)),
              ...(resolved.reasoning.defaultEffort === undefined
                ? {}
                : { defaultEffort: String(resolved.reasoning.defaultEffort) }),
            }
          }
        } catch {}
        return {
          id: m.id,
          name: m.name,
          ...(m.description === undefined ? {} : { description: m.description }),
          ...(m.inputModalities === undefined ? {} : { inputModalities: [...m.inputModalities] }),
          ...(reasoning === undefined ? {} : { reasoning }),
        }
      })),
    }
  }

  /** llm/discoverModels：测试连接 / 获取远端模型列表（llm-pi-ai 命名空间） */
  async discoverModels(params) {
    const llm = this.ctx.get('llm')
    if (llm === undefined) throw new Error('llm service unavailable')
    const request = {}
    if (params.provider !== undefined && params.provider !== '') request.provider = String(params.provider)
    if (params.baseURL !== undefined && params.baseURL !== '') request.baseURL = String(params.baseURL)
    if (params.api !== undefined && params.api !== '') request.api = String(params.api)
    if (params.apiKey !== undefined && params.apiKey !== '') request.apiKey = String(params.apiKey)
    try {
      const models = await llm.discoverModels('llm-pi-ai', request)
      return { models }
    } catch (error) {
      // 已保存过 provider 但没存 Key 时，核心 discovery 会先抛 MISSING_CREDENTIAL；
      // 这类端点的模型列表可能本来就是公开的（如 OpenCode Go /v1/models），
      // 去掉 provider 重试一次，让 llm-pi-ai 以未认证方式询问端点。
      if (error?.code !== 'MISSING_CREDENTIAL' || request.provider === undefined) throw error
      const publicRequest = { ...request, provider: undefined }
      const models = await llm.discoverModels('llm-pi-ai', publicRequest)
      return { models }
    }
  }

  /** llm/testModel：对单个模型发最小 chat 请求测连通 */
  async testModel(params) {
    const provider = String(params.provider ?? '')
    const model = String(params.model ?? '')
    if (provider === '' || model === '') throw new Error('llm/testModel: provider and model are required')
    const llm = this.ctx.get('llm')
    if (llm === undefined) throw new Error('llm service unavailable')
    const message = createUserMessage({ content: [{ type: 'text', text: 'ping' }], source: { kind: 'user' } })
    const chunks = llm.stream({ provider, model, messages: [message] })
    for await (const chunk of chunks) {
      if (chunk.type === 'finish') {
        if (chunk.reason?.kind === 'error') {
          const msg = chunk.reason?.failure?.message ?? '模型请求失败'
          throw new Error('testModel 失败: ' + msg)
        }
        return { ok: true, model }
      }
    }
    return { ok: true, model }
  }

  /** settings/describe：读取某 namespace 的 redacted descriptor */
  describeSettings(params) {
    const settings = this.ctx.get('settings')
    if (settings === undefined) return { namespaces: [] }
    const ns = params?.ns !== undefined && params.ns !== '' ? String(params.ns) : 'llm-pi-ai'
    const hit = settings.describe({ redactSecrets: true }).find((d) => d.ns === ns)
    if (hit === undefined) return { namespaces: [] }
    return { namespaces: [{ ns: hit.ns, value: hit.value, revision: hit.revision, ...(hit.user === undefined ? {} : { user: hit.user }) }] }
  }

  /** settings/setProvider：写 provider profile + 对应 credential */
  async setProvider(params) {
    const provider = String(params.provider ?? '')
    if (provider === '') throw new Error('settings/setProvider: provider is required')
    const settings = this.ctx.get('settings')
    if (settings === undefined) throw new Error('settings service unavailable')
    const credentials = this.ctx.get('credentials')

    const ref = this.providerCredentialRef(provider)
    // 未传 apiKey 时保留已有 credential；传空串才删除，避免“留空沿用已保存 Key”反而清掉 Key
    const apiKey = params.apiKey !== undefined && params.apiKey !== null ? String(params.apiKey) : undefined
    if (credentials !== undefined && apiKey !== undefined) {
      if (apiKey.length > 0) await credentials.set(ref, apiKey)
      else await credentials.unset(ref)
    }

    const profile = { apiKeyEnv: ref }
    if (params.displayName !== undefined && params.displayName !== '') profile.displayName = String(params.displayName)
    if (params.baseURL !== undefined && params.baseURL !== '') profile.baseURL = String(params.baseURL)
    if (params.api !== undefined && params.api !== '') profile.api = String(params.api)
    if (params.models !== undefined) profile.models = params.models
    // compat（思考参数方言 thinkingFormat 等）随 profile 整体 set：
    // 不传 = 清除交回 pi-ai detectCompat 自动探测，与 UI 的「自动」档语义一致
    if (params.compat !== undefined && params.compat !== null && typeof params.compat === 'object'
      && !Array.isArray(params.compat)) profile.compat = params.compat

    const expectedRevision = params.expectedRevision !== undefined ? Number(params.expectedRevision) : undefined
    await settings.mutate('llm-pi-ai', [{ op: 'set', path: ['providers', provider], value: profile }], expectedRevision)

    const desc = settings.describe({ redactSecrets: true }).find((d) => d.ns === 'llm-pi-ai')
    return { provider, revision: desc?.revision ?? 0 }
  }

  /** settings/updateProviderModels：只更新 provider 的模型列表，不触碰 baseURL/API Key 等配置 */
  async updateProviderModels(params) {
    const provider = String(params.provider ?? '')
    if (provider === '') throw new Error('settings/updateProviderModels: provider is required')
    if (params.models === undefined) throw new Error('settings/updateProviderModels: models is required')
    const settings = this.ctx.get('settings')
    if (settings === undefined) throw new Error('settings service unavailable')
    const expectedRevision = params.expectedRevision !== undefined ? Number(params.expectedRevision) : undefined
    await settings.mutate('llm-pi-ai', [{ op: 'set', path: ['providers', provider, 'models'], value: params.models }], expectedRevision)
    const desc = settings.describe({ redactSecrets: true }).find((d) => d.ns === 'llm-pi-ai')
    return { provider, revision: desc?.revision ?? 0 }
  }

  /** settings/removeProvider：删除 provider profile + 对应 credential */
  async removeProvider(params) {
    const provider = String(params.provider ?? '')
    if (provider === '') throw new Error('settings/removeProvider: provider is required')
    const settings = this.ctx.get('settings')
    if (settings === undefined) throw new Error('settings service unavailable')
    const credentials = this.ctx.get('credentials')
    const expectedRevision = params.expectedRevision !== undefined ? Number(params.expectedRevision) : undefined
    await settings.mutate('llm-pi-ai', [{ op: 'unset', path: ['providers', provider] }], expectedRevision)
    if (credentials !== undefined) await credentials.unset(this.providerCredentialRef(provider))
    return { removed: true }
  }

  /** 扩展方法路由：自定义方法优先，其余交给官方实现 */
  async handleRequest(method, params) {
    switch (method) {
      case 'ping': return this.ping()
      case 'session/cancel': return this.cancelSession(params ?? {})
      case 'session/bash': return this.runBash(params ?? {})
      case 'session/bashCancel': return this.cancelBash(params ?? {})
      case 'session/setModel': return this.setModel(params ?? {})
      case 'session/stats': return this.sessionStats(params ?? {})
      case 'llm/providers': return this.listProviders()
      case 'llm/models': return this.listModels(params ?? {})
      case 'llm/discoverModels': return this.discoverModels(params ?? {})
      case 'llm/testModel': return this.testModel(params ?? {})
      case 'settings/describe': return this.describeSettings(params ?? {})
      case 'settings/setProvider': return this.setProvider(params ?? {})
      case 'settings/updateProviderModels': return this.updateProviderModels(params ?? {})
      case 'settings/removeProvider': return this.removeProvider(params ?? {})
      case 'session/attachImages': return this.attachImages(params ?? {})
      case 'session/imageLimits': return this.imageLimits()
      case 'session/command': return this.sessionCommand(params ?? {})
      case 'session/query': return this.sessionQuery(params ?? {})
      case 'session/answerQuestion': return this.answerQuestion(params ?? {})
      case 'presets/list': return this.presetsList()
      case 'presets/read': return this.presetsRead(params ?? {})
      case 'presets/delete': return this.presetsDelete(params ?? {})
      case 'personas/list': return this.personasList()
      case 'personas/reorder': return this.personasReorder(params ?? {})
      default: return super.handleRequest(method, params)
    }
  }

  /**
   * 停止指定会话正在生成的回合。
   * 复用 DSH 原生 Agent.cancel()；turn/end（reason.kind='aborted'）随后由
   * session.event 通知送达。
   */
  async cancelSession(params) {
    const sessionId = String(params.sessionId ?? '')
    if (sessionId === '') throw new Error('session/cancel: sessionId is required')
    const agent = this.ctx.agents.get(SessionId(sessionId))
    if (agent === undefined) return { cancelled: false, reason: 'no live agent for session' }
    agent.cancel({ kind: 'user' })
    return { cancelled: true }
  }

  /**
   * 执行一条终端命令（后台执行 + 流式转发输出）。
   * 输出经 session.bashOutput 通知（{requestId, delta?, lossy?}）增量送达；
   * 超时（timeoutMs，经 shell 配置默认/封顶）或 session/bashCancel 都会中止命令。
   */
  async runBash(params) {
    const requestId = String(params.requestId ?? '')
    const command = String(params.command ?? '')
    if (command === '') throw new Error('session/bash: command is required')
    const ac = new AbortController()
    const spec = this.ctx.shell.resolve({
      command,
      ...(params.workdir !== undefined ? { workdir: String(params.workdir) } : {}),
      ...(params.timeoutMs !== undefined ? { timeoutMs: Number(params.timeoutMs) } : {}),
      signal: ac.signal,
    })
    const proc = this.ctx.shell.start(spec)
    const entry = { proc, abort: () => ac.abort('bash cancelled') }
    this.runningBash.set(requestId, entry)
    // start() 不带超时（后台语义），这里按 resolve 出的 timeoutMs 自己管
    const timeoutTimer = setTimeout(() => ac.abort('bash timeout'), spec.timeoutMs)
    const streamTimer = setInterval(
      () => this.flushBashOutput(proc, requestId),
      this.streamIntervalMs,
    )
    try {
      await proc.done
    } finally {
      clearTimeout(timeoutTimer)
      clearInterval(streamTimer)
      this.runningBash.delete(requestId)
    }
    this.flushBashOutput(proc, requestId) // 最终排空
    const abortedBy = ac.signal.aborted ? String(ac.signal.reason ?? 'aborted') : null
    return {
      status: proc.status,
      exitCode: proc.exitCode,
      signal: proc.signal,
      timedOut: abortedBy === 'bash timeout',
      cancelled: abortedBy === 'bash cancelled',
    }
  }

  /** 心跳探测：进程活着且可响应即返回（App 保活 worker 用） */
  async ping() {
    return { pong: true, liveSessions: this.ctx.agents.list().length }
  }

  /**
   * 会话发图：接收 canonical base64 图片批 → 附件服务规范化入库 → 返回 durable refs。
   * App 后续用返回的 refs 构造 session/prompt 的 contentBlocks（text + image 块）。
   */
  async attachImages(params) {
    const attachments = this.ctx.get('attachments')
    if (attachments === undefined) {
      throw new Error('session/attachImages: attachments service is not mounted')
    }
    const images = Array.isArray(params.images) ? params.images : []
    if (images.length === 0) throw new Error('session/attachImages: images must be a non-empty array')
    const inputs = images.map((img, index) => {
      if (typeof img?.data !== 'string' || img.data.length === 0) {
        throw new Error(`session/attachImages: images[${index}].data (canonical base64) is required`)
      }
      if (typeof img?.mediaType !== 'string'
        || !['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(img.mediaType)) {
        throw new Error(`session/attachImages: images[${index}].mediaType must be one of image/jpeg|image/png|image/webp|image/gif`)
      }
      return {
        mediaType: img.mediaType,
        data: img.data,
        ...(typeof img.name === 'string' && img.name.length > 0 ? { name: img.name } : {}),
      }
    })
    try {
      const refs = await admitEncodedImages(attachments, inputs)
      return { refs: [...refs] }
    } catch (error) {
      const code = error?.code !== undefined ? String(error.code) : undefined
      throw new Error(
        `session/attachImages failed${code === undefined ? '' : ` (${code})`}: ${error instanceof Error ? error.message : String(error)}`,
      )
    }
  }

  /** 图片限额：App 端压缩参数上限预取用。 */
  imageLimits() {
    const attachments = this.ctx.get('attachments')
    if (attachments === undefined) {
      throw new Error('session/imageLimits: attachments service is not mounted')
    }
    return { imageLimits: attachments.imageLimits }
  }

  /** 会话调用量统计：读持久化日志折叠（轮/步/时长/首token/tok/s/用量/最近一步/上下文） */
  async sessionStats(params) {
    const sessionId = params?.sessionId === undefined || params.sessionId === null
      ? '' : String(params.sessionId)
    if (sessionId === '') return { stats: null }
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) return { stats: null }
    let inspection
    try {
      inspection = await persistence.load(SessionId(sessionId))
    } catch {
      return { stats: null }
    }
    return { stats: foldSessionStats(inspection.events) }
  }

  /**
   * 程序化执行一条斜杠命令（plan-standard-mode §三.5，附加模式胶囊的执行通道）。
   *
   * 与 prompt 的差异：命令不进模型回合（CommandRuntime 只追加 command/run、command/done
   * 日志事件并执行 handler）；/plan、/goal、/compact 等 handler 直接改会话状态。禁止把
   * 斜杠文本当普通消息发送 —— 命令拦截不在 SDK 层，原文会进模型。
   *
   * 参数带 presetId/cwd 时先写 pendingHints → 会话未建则创建（归属即正确，§二.1 推论）。
   * 超时保护：5s 只放弃等待、不 abort 传给 execute 的 signal —— plan 在 busy 会话是
   * queued 立即返回、可能已生效，abort 会把 handler settle 成 error，UI 误报失败
   * （§三.5）。execute 对未知语法/命令返回 undefined → COMMAND_UNKNOWN；commands
   * 服务未挂载 → COMMAND_UNAVAILABLE；handler 抛错 → kind:'error' 带原文。
   */
  async sessionCommand(params) {
    const sessionId = String(params.sessionId ?? '')
    if (sessionId === '') throw new Error('session/command: sessionId is required')
    const line = String(params.line ?? '')
    if (line === '') throw new Error('session/command: line is required')
    this.stashHints(sessionId, params)
    let rec
    try {
      rec = await this.getOrCreateSession(sessionId)
    } catch (error) {
      throwMappedPresetError(error)
    }
    const agent = rec.handle.agent
    if (this.ctx.agents.get(agent.id) !== agent) {
      throw new Error(`session/command: session agent was disposed outside the server: ${sessionId}`)
    }
    const commands = this.ctx.get('commands')
    if (commands === undefined) {
      throw meowRpcError(RPC_ERROR.COMMAND_UNAVAILABLE, 'session/command: commands service is not mounted', {
        code: 'COMMAND_UNAVAILABLE',
      })
    }
    // 自己的 signal 传给 execute（本次永不 abort，仅占位）；5s 超时只放弃等待。
    const controller = new AbortController()
    const execution = Promise.resolve()
      .then(() => commands.execute(agent, line, [], controller.signal))
    // 超时放弃等待后，迟到的 rejection 不允许变成 unhandledRejection 打崩进程
    execution.catch(() => {})
    const settled = await Promise.race([
      execution.then(
        (value) => ({ done: true, value }),
        (error) => ({ done: true, error }),
      ),
      new Promise((resolve) => { setTimeout(() => resolve({ done: false }), 5000) }),
    ])
    if (!settled.done) {
      return {
        sessionId,
        kind: 'success',
        text: '命令已受理（5s 内未返回执行结果；是否生效以会话事件为准）',
      }
    }
    if (settled.error !== undefined) {
      // handler 抛错：command/done(error) 已落日志；这里把原因带回 UI
      return {
        sessionId,
        kind: 'error',
        text: settled.error instanceof Error ? settled.error.message : String(settled.error),
      }
    }
    if (settled.value === undefined) {
      throw meowRpcError(
        RPC_ERROR.COMMAND_UNKNOWN,
        `session/command: "${line}" did not resolve to a registered command`,
        { code: 'COMMAND_UNKNOWN', line },
      )
    }
    const result = settled.value.result ?? {}
    return {
      sessionId,
      kind: result.kind === 'error' ? 'error' : 'success',
      ...(result.text !== undefined ? { text: String(result.text) } : {}),
    }
  }

  /**
   * 旧会话状态水合（plan-standard-mode §三.7）：读持久化日志做 last-wins 折叠。
   * resume 不重放种子事件，App 打开旧会话时用本方法恢复 todo / 附加模式（plan/goal）/
   * 所属预设；blank = 无 user/message 与 turn 事件。
   */
  async sessionQuery(params) {
    const sessionId = params?.sessionId === undefined || params.sessionId === null
      ? '' : String(params.sessionId)
    if (sessionId === '') {
      return { sessionId: '', preset: null, blank: true, todos: null, plan: null, goal: null }
    }
    const empty = { sessionId, preset: null, blank: true, todos: null, plan: null, goal: null }
    const persistence = this.ctx.get('sessionPersistence')
    if (persistence === undefined) return empty
    let inspection
    try {
      inspection = await persistence.load(SessionId(sessionId))
    } catch {
      return empty
    }
    const events = inspection.events
    let todos = null
    let plan = null
    let goal = null
    for (const event of events) {
      if (event.type === 'todo/write') {
        todos = Array.isArray(event.data?.todos) ? event.data.todos : todos
      } else if (event.type === 'plan/mode') {
        plan = { active: event.data?.active === true }
      } else if (event.type === 'goal/change') {
        // goal/change 携带变更后全量状态（clear 为墓碑）；last-wins
        goal = event.data?.operation === 'clear' ? null : event.data
      }
    }
    const blank = !events.some((event) => event.type === 'user/message' || event.type === 'turn/start')
    // preset 与 resume 重挂同源：header（创建时值）+ agent-preset/selected 事件（空白期切换）
    let preset = null
    try {
      preset = resolveSessionPreset({ header: inspection.meta, events }) ?? null
    } catch {
      preset = null
    }
    return { sessionId, preset, blank, todos, plan, goal }
  }

  /**
   * Agent 预设名单（§三.1，自动扫描接口）：roster.list() 每次重读 roots，无缓存 ——
   * 用户/AI 新建的预设自动出现在返回值，App 不硬编码列表。isDefault 对照默认预设 id
   * （默认缺失/解析失败时不标注任何一行，避免整个方法失败）。
   */
  async presetsList() {
    const presets = this.presetsService()
    if (presets === undefined) return { presets: [] }
    const roster = await presets.list()
    let defaultId
    try {
      defaultId = (await presets.resolve()).id
    } catch {
      defaultId = undefined
    }
    return {
      presets: roster.map((preset) => ({
        id: preset.id,
        ...(preset.name !== undefined ? { name: preset.name } : { name: preset.id }),
        ...(preset.description !== undefined ? { description: preset.description } : {}),
        trust: preset.trust,
        broken: preset.broken ?? null,
        isDefault: preset.id === defaultId,
      })),
    }
  }

  /** 读预设组合全文（§三.2，创造预设用）；未知 id → PRESET_UNKNOWN */
  async presetsRead(params) {
    const presets = this.presetsService()
    const id = String(params?.id ?? '')
    if (presets === undefined) {
      throw meowRpcError(RPC_ERROR.PRESET_UNKNOWN, 'presets/read: agent-presets service is not mounted', {
        code: 'PRESET_UNKNOWN', presetId: id, available: [],
      })
    }
    let composition
    try {
      composition = await presets.read(id)
    } catch (error) {
      throwMappedPresetError(error)
    }
    return { id, composition }
  }

  /** 删用户预设（§三.3）：trust=user 才可删（内置预设 PRESET_IMMUTABLE） */
  async presetsDelete(params) {
    const presets = this.presetsService()
    const id = String(params?.id ?? '')
    if (presets === undefined) {
      throw meowRpcError(RPC_ERROR.PRESET_UNKNOWN, 'presets/delete: agent-presets service is not mounted', {
        code: 'PRESET_UNKNOWN', presetId: id, available: [],
      })
    }
    let preset
    try {
      preset = await presets.resolve(id)
    } catch (error) {
      throwMappedPresetError(error)
    }
    if (preset.trust !== 'user') {
      throw meowRpcError(RPC_ERROR.PRESET_IMMUTABLE, `preset "${id}" is built into the app and cannot be deleted`, {
        code: 'PRESET_IMMUTABLE', presetId: id, trust: preset.trust,
      })
    }
    await presets.remove(id)
    return { deleted: true }
  }

  /**
   * App 回答回来（§三.6）：按 requestId 取 pending 条目并 settle。cancelled=true 以
   * ASK_CANCELLED 拒绝（上游 plan-mode 对该 code 有专门文案：「用户想直接说话」）。
   */
  async answerQuestion(params) {
    const requestId = String(params?.requestId ?? '')
    const entry = this.pendingQuestions.get(requestId)
    if (entry === undefined) {
      throw new Error('session/answerQuestion: unknown or already-settled requestId')
    }
    this.pendingQuestions.delete(requestId)
    entry.cleanup?.()
    if (params?.cancelled === true) {
      entry.reject(new UserQuestionError('the user dismissed the question', 'ASK_CANCELLED'))
      return { delivered: true }
    }
    const raw = Array.isArray(params?.answers) ? params.answers : []
    const answers = raw.map((item) => ({
      id: String(item?.id ?? ''),
      selected: Array.isArray(item?.selected) ? item.selected.map((value) => String(value)) : [],
      ...(item?.custom !== undefined && item.custom !== null ? { custom: String(item.custom) } : {}),
    }))
    entry.resolve({ answers })
    return { delivered: true }
  }

  /**
   * 注册问答 provider（连接建立时调用，§4.4④）。
   *
   * 生命周期要点：serve() 每条 socket 连接 new 一个 server 实例，而
   * UserQuestionService 全局单 provider（重复注册抛 DUPLICATE_PROVIDER）——
   * provider 必须挂连接生命周期：连接建立 registerProvider，socket close 的
   * cleanup 里 dispose 并 reject 本实例全部 pending（重连才不会撞单槽限制）。
   * 重连竞态（旧连接尚未清理、新连接先到）注册失败 → 本连接问答通道不可用，
   * 工具调用会收到 NO_PROVIDER，App 重连后恢复；不写任何 stdout 日志（会污染
   * JSON-RPC 帧）。
   */
  installQuestionProvider() {
    const userQuestions = this.ctx.get('userQuestions')
    if (userQuestions === undefined) return
    try {
      this.questionProviderDisposer = userQuestions.registerProvider({
        ask: (request) => this.askUser(request),
      })
    } catch {
      this.questionProviderDisposer = undefined
    }
  }

  /** 释放问答 provider 并 reject 本连接全部 pending（socket close cleanup 调用） */
  disposeQuestionProvider() {
    const disposer = this.questionProviderDisposer
    this.questionProviderDisposer = undefined
    if (disposer !== undefined) {
      try {
        disposer()
      } catch {
        // 单槽已被并发重连的下一个实例占用等场景：释放失败不阻塞连接清理
      }
    }
    const error = new UserQuestionError('the chat UI disconnected before answering', 'ASK_ABORTED')
    for (const entry of this.pendingQuestions.values()) {
      try {
        entry.cleanup?.()
      } catch {
        // 清理失败同样不阻塞
      }
      entry.reject(error)
    }
    this.pendingQuestions.clear()
  }

  /**
   * 问答 provider 本体：生成 requestId、登记 pending、以 session.question 通知送达
   * App，返回等待回答的 Promise。abort signal（用户停止生成 / 回合中止）→ reject
   * ASK_ABORTED；sessionId 取 request.agent?.session.id（ask/plan 两条路径都带 agent）。
   */
  askUser(request) {
    const requestId = randomUUID()
    const agent = request?.agent
    const sessionId = agent !== undefined && agent?.session !== undefined ? String(agent.session.id) : undefined
    return new Promise((resolve, reject) => {
      const entry = { resolve, reject, cleanup: undefined }
      const signal = request?.signal
      if (signal !== undefined) {
        const onAbort = () => {
          if (this.pendingQuestions.get(requestId) === entry) this.pendingQuestions.delete(requestId)
          reject(new UserQuestionError('ask_user_question was aborted before the user answered', 'ASK_ABORTED'))
        }
        signal.addEventListener('abort', onAbort, { once: true })
        entry.cleanup = () => signal.removeEventListener('abort', onAbort)
      }
      this.pendingQuestions.set(requestId, entry)
      if (signal !== undefined && signal.aborted) {
        // 已中止：登记后立即按 ASK_ABORTED 出栈（复用 abort 监听器的语义）
        this.pendingQuestions.delete(requestId)
        entry.cleanup()
        reject(new UserQuestionError('ask_user_question was aborted before the user answered', 'ASK_ABORTED'))
        return
      }
      this.transport.notify('session.question', {
        ...(sessionId !== undefined ? { sessionId } : {}),
        requestId,
        questions: request?.questions ?? [],
      })
    })
  }

  /** 中止一条正在运行的终端命令（幂等） */
  async cancelBash(params) {
    const requestId = String(params.requestId ?? '')
    const entry = this.runningBash.get(requestId)
    if (entry === undefined) return { cancelled: false }
    entry.abort()
    return { cancelled: true }
  }

  /** 读取增量输出并以 session.bashOutput 通知转发 */
  flushBashOutput(proc, requestId) {
    const read = proc.readOutput()
    if (read.delta === '' && !read.lossy) return
    const payload = { requestId }
    if (read.delta !== '') payload.delta = read.delta
    if (read.lossy) payload.lossy = true
    this.transport.notify('session.bashOutput', payload)
  }
}

/**
 * 挂载传输并接管进程生命周期（与官方 sdk-jsonrpc-server 的 apply 同构）：
 * shutdown 响应写出后 flush → 销毁根 fiber → exit(0)。
 *
 * 两种传输模式：
 *   - socket 模式（DSH_JSONRPC_SOCKET 已设）：DSH 跑在真终端里，stdio 已被 PTY 占用，
 *     聊天 JSON-RPC 走本地 unix socket；每个连接复用一个 MeowJsonRpcServer（会话经
 *     ctx.agents + 持久化 resume 共享，重连自动恢复）。
 *   - stdio 模式（未设 DSH_JSONRPC_SOCKET）：沿用 process.stdin/stdout（PC 调试）。
 */
export function apply(ctx, config) {
  const resolved = config
  const exit = (code) => process.exit(code)

  // ── 记忆系统注入（plan-memory-execution §1.2–§1.5）────────────────────────
  // 角色层 + 记忆层由一个独立 section 全权负责（order 50，排在基座 persona 之后、
  // 工具指引之前）：不依赖基座 persona 内的变量引用，避免被自建预设的 dsh-persona 遮蔽。
  // 内容全部走 ensureSnapshot 单点 —— 首条消息固化，后续组装复用同一份文本，
  // system prompt 前缀恒定（KV 缓存可命中）。两开关均 OFF 时返回空串（renderPrompt 丢弃空 section）。
  ctx.systemPrompt.section({
    name: 'meow:memory',
    order: 50,
    text: (context) => {
      try {
        return memorySectionText(context)
      } catch (error) {
        // section provider 抛异常会炸掉整个 turn：兜底成「不注入」，下次组装重试
        ctx.logger?.warn?.(`meow-jsonrpc: memory section failed: ${error instanceof Error ? error.message : String(error)}`)
        return ''
      }
    },
  })

  // ── 向后兼容的 prompt 变量（§1.4/§1.5）───────────────────────────────────
  // 基座与官方预设不再引用 {{soul}}/{{user}}（人格注入归上方 section，避免重复）；
  // 保留注册只为兼容引用了它们的旧自建预设。取值一律从快照（不再实时读文件），
  // 与 section 同源，故同一会话内前缀稳定。变量必须无条件注册：persona 的 {{...}}
  // 引用是严格插值，缺变量会让所有会话组装报错。
  // 变量名只能小写（system-prompt 的 VARIABLE_NAME = /^[a-z][a-z0-9_]*$/），大写会炸严格插值
  ctx.systemPrompt.variable('soul_path', (context) => {
    const id = context?.agent?.id
    const personaId = id !== undefined
      ? (memorySnapshots.get(String(id))?.persona?.id ?? resolvePersonaId(memoryConfigs.get(String(id))?.personaId))
      : resolvePersonaId(undefined)
    return personaId === null ? '' : `${agentsRoot()}/personas/${personaId}/SOUL.md`
  })
  ctx.systemPrompt.variable('user_path', (context) => {
    const id = context?.agent?.id
    const personaId = id !== undefined
      ? (memorySnapshots.get(String(id))?.persona?.id ?? resolvePersonaId(memoryConfigs.get(String(id))?.personaId))
      : resolvePersonaId(undefined)
    return personaId === null ? '' : `${agentsRoot()}/personas/${personaId}/USER.md`
  })
  // 角色开关 OFF → 两个内容变量都返回空串（design-memory-system §五.2）
  ctx.systemPrompt.variable('soul', (context) => {
    const id = context?.agent?.id
    if (id === undefined) return ''
    const key = String(id)
    if (memoryConfigs.get(key)?.personaEnabled === false) return ''
    return ensureSnapshot(key).persona?.soul?.trim() ?? ''
  })
  ctx.systemPrompt.variable('user', (context) => {
    const id = context?.agent?.id
    if (id === undefined) return ''
    const key = String(id)
    if (memoryConfigs.get(key)?.personaEnabled === false) return ''
    return ensureSnapshot(key).persona?.user?.trim() ?? ''
  })

  // 会话销毁清理常驻 Map（design-memory-system §内存 Map 生命周期）：
  // 快照文件由 App 删 Room 行时清理，这里只回收进程内内存。
  ctx.on('agent/disposed', ({ agent }) => {
    const key = String(agent.id)
    memoryConfigs.delete(key)
    memorySnapshots.delete(key)
  })

  let exitTask
  const disposeAndExit = (transport) => {
    exitTask ??= (async () => {
      await Promise.allSettled([Promise.resolve().then(() => transport.flush())])
      await Promise.allSettled([Promise.resolve().then(() => ctx.root.fiber.dispose())])
      exit(0)
    })()
    return exitTask
  }

  /** 为一条连接挂载传输；返回清理函数（释放问答 provider + dispose server + 关传输） */
  const serve = (transport) => {
    const server = new MeowJsonRpcServer(ctx, transport, {
      maxTokensAsSuccess: resolved.maxTokensAsSuccess,
      streamIntervalMs: resolved.bashStreamIntervalMs,
    })
    // 问答 provider 挂连接生命周期：UserQuestionService 全局单 provider 槽位，
    // socket close 的 cleanup 里释放并 reject 本连接全部 pending（§4.4④）
    server.installQuestionProvider()
    transport.onRequest(async (method, params) => {
      const result = await server.handleRequest(method, params ?? {})
      if (method === 'shutdown') {
        setImmediate(() => { void disposeAndExit(transport) })
      }
      return result
    })
    transport.start()
    return async () => {
      server.disposeQuestionProvider()
      await server.shutdown()
      transport.close()
    }
  }

  const socketPath = process.env.DSH_JSONRPC_SOCKET

  if (socketPath) {
    // socket 模式：监听本地 unix socket（路径由 App 经环境变量注入，位于 filesDir）；
    // 用喵仓版行传输（支持结构化 RPC 错误 code/data）。每个连接复用一个
    // MeowJsonRpcServer（会话经 ctx.agents + 持久化 resume 共享，重连自动恢复）。
    const server = createServer((socket) => {
      const cleanup = serve(new MeowJsonRpcTransport(socket, socket))
      socket.on('close', () => { void cleanup() })
      socket.on('error', () => {})
    })
    server.on('error', () => {})
    server.listen(socketPath)
    ctx.effect(() => {
      return () => { server.close() }
    }, 'meow-jsonrpc.serve')
  } else {
    // stdio 模式（fallback，PC 调试 / 无真终端时）
    const cleanup = serve(new MeowJsonRpcTransport(process.stdin, process.stdout))
    ctx.effect(() => {
      return cleanup
    }, 'meow-jsonrpc.serve')
  }
}