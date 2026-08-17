/**
 * meow-jsonrpc —— 喵学堂自定义 JSON-RPC 插件。
 *
 * 不 fork DSH 源码：复用官方导出的 HarnessSdkJsonRpcServer（标准方法
 * initialize / session/prompt / shutdown 与 session.event / session.status 通知）
 * 与 JsonRpcLineTransport（stdio 新行分隔 JSON-RPC 2.0 传输），通过子类扩展：
 *
 *   - session/cancel    {sessionId}            → agent.cancel({kind:'user'})（DSH 原生 API）
 *   - session/bash      {requestId,command,workdir?,timeoutMs?} → ctx.shell 后台执行，
 *                         期间以 session.bashOutput 通知流式转发增量，返回 exitCode 等
 *   - session/bashCancel {requestId}           → 中止正在运行的终端命令
 *
 * cordis.yml 中本插件取代官方 sdk-jsonrpc-server（两者都独占 stdin/stdout，
 * 不能共存）；其余能力由组合里的官方插件提供。
 */

import { createServer } from 'node:net'
import { JsonRpcLineTransport } from '@deepseek-ai/dsh-sdk-protocol'
import { HarnessSdkJsonRpcServer } from '@deepseek-ai/dsh-sdk-jsonrpc-server'
import { SessionId } from '@deepseek-ai/dsh-session'
import { installModelSelection } from '@deepseek-ai/dsh-agent'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import z from '@deepseek-ai/schemastery'

export const name = 'meow-jsonrpc'

/** 需要 agents（cancel/官方 server 建会话）与 shell（终端命令）两个服务 */
export const inject = ['agents', 'shell']

/** 插件配置：全部可选，缺省值即喵学堂部署形态 */
export const Config = z.object({
  /** max-token 结束的回合按成功上报（与官方部署一致：手机上宁可按成功展示） */
  maxTokensAsSuccess: z.boolean().default(true),
  /** session.bashOutput 流式转发的轮询间隔（毫秒） */
  bashStreamIntervalMs: z.number().default(80),
})

/** 终端输出流式转发的轮询间隔上限检查由调用处兜底；这里只做安全下限 */
const MIN_STREAM_INTERVAL_MS = 20

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
 * 喵学堂版 SDK server：官方标准方法全部走 super，仅扩展三个自定义方法。
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
   * installModelSelection（运行时切换 provider/model/reasoningEffort），
   * 同 sessionId 复用 live agent + selection。磁盘已有持久化日志时走 resume。
   * TS 的 private 只是编译期约束，运行时属性照常存在；本插件锁定 DSH rc.5。
   */
  async getOrCreateSession(sessionId) {
    if (this.shuttingDown) throw new Error('SDK server is shutting down')
    if (this.sessions.has(sessionId)) return this.sessions.get(sessionId)
    const pending = this.resumeCreations.get(sessionId)
    if (pending) return pending
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
      const setup = (agentCtx) => {
        installModelSelection(agentCtx, selection)
        this.selections.set(sessionId, selection)
      }
      let handle
      if (persistence !== undefined) {
        try {
          handle = await this.ctx.agents.resume({
            resumeSessionId: SessionId(sessionId),
            agentOptions: this.agentOptionsFor(),
            setup,
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
          // 磁盘无此会话 → 全新创建
          handle = await this.ctx.agents.create({
            sessionId: SessionId(sessionId),
            meta: { cwd: this.cwd },
            agentOptions: this.agentOptionsFor(),
            setup,
          })
        }
      } else {
        handle = await this.ctx.agents.create({
          sessionId: SessionId(sessionId),
          meta: { cwd: this.cwd },
          agentOptions: this.agentOptionsFor(),
          setup,
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

  /** llm/models：某 provider 的模型目录 */
  async listModels(params) {
    const provider = String(params.provider ?? '')
    if (provider === '') throw new Error('llm/models: provider is required')
    const llm = this.ctx.get('llm')
    if (llm === undefined) throw new Error('llm service unavailable')
    const models = await llm.listModels(provider)
    return { models: models.map((m) => ({ id: m.id, name: m.name, ...(m.description === undefined ? {} : { description: m.description }) })) }
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
    const models = await llm.discoverModels('llm-pi-ai', request)
    return { models }
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
    const apiKey = params.apiKey !== undefined ? String(params.apiKey) : ''
    if (credentials !== undefined) {
      if (apiKey.length > 0) await credentials.set(ref, apiKey)
      else await credentials.unset(ref)
    }

    const profile = { apiKeyEnv: ref }
    if (params.displayName !== undefined && params.displayName !== '') profile.displayName = String(params.displayName)
    if (params.baseURL !== undefined && params.baseURL !== '') profile.baseURL = String(params.baseURL)
    if (params.api !== undefined && params.api !== '') profile.api = String(params.api)
    if (params.models !== undefined) profile.models = params.models

    const expectedRevision = params.expectedRevision !== undefined ? Number(params.expectedRevision) : undefined
    await settings.mutate('llm-pi-ai', [{ op: 'set', path: ['providers', provider], value: profile }], expectedRevision)

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
      case 'llm/providers': return this.listProviders()
      case 'llm/models': return this.listModels(params ?? {})
      case 'llm/discoverModels': return this.discoverModels(params ?? {})
      case 'llm/testModel': return this.testModel(params ?? {})
      case 'settings/describe': return this.describeSettings(params ?? {})
      case 'settings/setProvider': return this.setProvider(params ?? {})
      case 'settings/removeProvider': return this.removeProvider(params ?? {})
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

  let exitTask
  const disposeAndExit = (transport) => {
    exitTask ??= (async () => {
      await Promise.allSettled([Promise.resolve().then(() => transport.flush())])
      await Promise.allSettled([Promise.resolve().then(() => ctx.root.fiber.dispose())])
      exit(0)
    })()
    return exitTask
  }

  /** 为一条连接挂载传输；返回清理函数（dispose server + 关传输） */
  const serve = (transport) => {
    const server = new MeowJsonRpcServer(ctx, transport, {
      maxTokensAsSuccess: resolved.maxTokensAsSuccess,
      streamIntervalMs: resolved.bashStreamIntervalMs,
    })
    transport.onRequest(async (method, params) => {
      const result = await server.handleRequest(method, params ?? {})
      if (method === 'shutdown') {
        setImmediate(() => { void disposeAndExit(transport) })
      }
      return result
    })
    transport.start()
    return async () => {
      await server.shutdown()
      transport.close()
    }
  }

  const socketPath = process.env.DSH_JSONRPC_SOCKET

  if (socketPath) {
    // socket 模式：监听本地 unix socket（路径由 App 经环境变量注入，位于 filesDir）
    const server = createServer((socket) => {
      const cleanup = serve(new JsonRpcLineTransport(socket, socket))
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
    const cleanup = serve(new JsonRpcLineTransport(process.stdin, process.stdout))
    ctx.effect(() => {
      return cleanup
    }, 'meow-jsonrpc.serve')
  }
}