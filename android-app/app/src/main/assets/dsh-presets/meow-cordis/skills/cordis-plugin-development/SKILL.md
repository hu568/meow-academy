---
name: cordis-plugin-development
description: 创建、修改、调试或扩展动态 Cordis 插件（Host 服务与事件、动态模型工具、版本更新、失败修复、运行时诊断）前必读 —— 把用户诉求路由到正确的 Inspect Provider，然后 define、run、修复或回滚插件。喵仓部署为 headless：插件只有 Host 半边，无 Client UI、无审批流。
---

# 开发动态 Cordis 插件（喵仓版）

先判断能力属于哪一侧（本部署只有 Host），写代码前先查真实接口。永远不要从服务名、事件名、示例去猜完整 API。

**部署范围（先读）**：本运行时跑在安卓 App 里、没有浏览器页面，所以插件**只有 Host 半边**：只提供 `code.host`，不写 `code.client`，不注册 Slots UI、主题覆盖、Tool 卡片、覆盖层 —— 没有任何页面会响应，带 Client 半边的包会永远停住。同理不存在审批流：host-only 的包 `cordis_run` 在同一次工具调用里同步激活完成。上游指引里所有 Client / 浏览器 / 审批相关内容在本部署一律不适用（`cordis_inspect_list` 也只会有 host 平面的 Provider）。

界面、外观、渲染类诉求不属于插件工作：改 `appconfig/*.jsonc` 配置文件（热更即时生效）或在会话工作区生成 HTML 文件。要可复用的会话模式（而非临时能力）走 Agent 预设创作，见 `editing-cordis-compositions` 技能。

## 标准工作流

1. 调 `cordis_inspect_list` 拿到当前 Host 注册的全部 Provider、方法与 schema。
2. 挑最小集合的 `cordis_inspect_query`，读准实现要用的 Services、Events、Builtins、Tools 的确切签名。
3. 新插件：设计第一个 Package；改现有插件：先 `cordis_inspect_self(pluginId, packageId)` 读基线源码与诊断。
4. `code.host` 里写纯 JavaScript 函数体，调 `cordis_define`。
5. 用 define 返回的 `pluginId` / `packageId` 调 `cordis_run` —— host-only 包同步激活，本次调用直接返回 `running` 或技术失败（含 `waiting`：插件声明的服务尚未就绪）。
6. 失败或 waiting 从返回值、steering 消息或 `cordis_inspect_self` 读取诊断再修。
7. 临时停用用 `cordis_stop`；确认不再需要才 `cordis_undefine`。

不需要等任何异步结果：激活在 `cordis_run` 调用内完成；插件后续注册的监听器/工具触发时，输出经 tagged console（终端页可见）或 steering 回流。

## 工具用法对照

| 工具 | 什么时候用 | 不要 |
| --- | --- | --- |
| `cordis_inspect_list` | 一次拿全当前 Provider 与方法 schema；运行时能力目录变化后刷新 | 硬编码 Provider 名跳过 list；把清单当业务数据用 |
| `cordis_inspect_query` | 写代码前确认 Service 方法、Event 模式、Builtin、Tool schema | 用它代替插件里真调服务；用查询结果当运行时数据 |
| `cordis_inspect_self` | 列当前插件、看版本指针、读确切的 Package 源码与运行时诊断 | 为了凑清单拉全部源码；用它去改或启动插件 |
| `cordis_define` | 创建插件首个版本 / 给现有插件追加不可变 Package | 以为 define 会执行 apply —— 它只校验参数与语法、登记源码 |
| `cordis_run` | 激活确切的 Package：首启/重启/回滚用 `run`，换版本用 `update` | 把 waiting 当成功；用 `run` 隐式换版本 |
| `cordis_stop` | 暂停当前效果，保留 Package、版本指针供以后再启 | 把 stop 当永久删除 |
| `cordis_undefine` | 永久删除插件及其全部 Package | 回滚、检查或重启还需要它的时候调 |

## Provider 导航

方法名从当前 `cordis_inspect_list` 结果里选。本部署 host 平面的四个 Provider：

- `Service.listService`：不带 `service` 返回全部可调服务及方法签名目录；带 `{"service": "agentPresets"}` 之类再查选中服务的访问规则、结构化方法描述/参数/返回值及其引用类型。
- `Event.listEvents`：不带 `event` 返回全部事件及监听签名；带 `{"event": "..."}` 查确切监听契约。Waterfall 监听器必须调并返回 `next()`。
- `Builtin.listBuiltins`：沙箱提供的全局符号与签名（`ctx` / `harness` / `console` / `btoa` / `atob` / `TextEncoder` / `TextDecoder`）—— 环境里有什么全局，以它为准。
- `Tool.listTools`：当前 agent 可见的全部工具 schema（含动态注册的），注册新工具前查重名。

Provider 名、方法名、输入都必须来自当前 list 结果。目录描述的是这个版本允许哪些接口，不保证服务此刻已挂载；运行时用真服务，别缓存或展示查询结果。

## 执行环境

`code.host` 是纯 JavaScript 函数体，返回一个 Cordis 插件对象。不经 TypeScript、JSX、打包器处理；它作为 async 函数体在 `node:vm` 沙箱里求值（`vmTimeoutMs` 5000ms 预算约束同步部分）。

可用全局（以 `Builtin.listBuiltins` 查到的为准）：`ctx`（受限 Cordis 上下文）、`harness`（`handle` / `defineTool` / `registerTool`）、tagged `console`、`btoa` / `atob` / `TextEncoder` / `TextDecoder`。

明确不可用（调用即报错，报错文案会指路替代品）：

- `import` / `require`（Node 模块不可用 —— 文件走 `fs` 服务、HTTP 走 `web` 服务、进程走 `bash` 服务，先 `cordis_inspect_query` 查签名）；
- `setTimeout` / `setInterval` 等原生定时器（用 `timer` 服务的 `ctx.timeout` / `ctx.interval`，见下）；
- `fetch`（用 `web` 服务）；
- TypeScript 类型、`as`、装饰器、JSX；`window` / `document` / `process` / `Buffer` 等 Node/浏览器全局。

正确（注册一个工具的纯 JS）：

```js
return {
  apply(ctx) {
    harness.registerTool(ctx, harness.defineTool({
      name: 'meow_meow_counter',
      description: '返回调用次数。',
      parameters: {},
      output: { schema: { type: 'string' }, render(_args, value) { return [{ type: 'text', text: value }] } },
      execute() { return Promise.resolve('喵 x1') },
    }))
  },
}
```

错误（TypeScript/JSX/import 全不允许）：

```jsx
return {
  apply(ctx) {
    const n: number = 1          // ✗ 类型标注
    import something from 'x'    // ✗ import
    return <div>{n}</div>        // ✗ JSX
  },
}
```

## 访问服务

默认用 `ctx.get(name)` 读可选能力并处理缺失：

```js
return {
  apply(ctx) {
    const service = ctx.get('serviceName')
    if (service === undefined) return
    service.someMethod()
  },
}
```

只有当服务是硬依赖、希望 Cordis 在服务出现后重新激活插件进入 waiting 时才声明 `inject`：

```js
return {
  inject: ['requiredService'],
  apply(ctx) {
    ctx.requiredService.someMethod()
  },
}
```

不要为了省一个 `undefined` 判断滥用 `inject`；没声明就访问 `ctx.requiredService` 会被 Guard 拒绝。

## 管理副作用

插件被 stop、update、undefine 后，每一份贡献都必须被移除。优先用 Cordis 生命周期 API：

- `ctx.on()` 注册事件监听；
- `ctx.effect()` 持有返回 disposer 的外部订阅；
- 服务、工具、定时器 API 返回的 disposer 都要保留；
- 不要在 `apply()` 之外或模块作用域制造进程级副作用。

```js
return {
  apply(ctx) {
    const service = ctx.get('serviceName')
    if (service === undefined) return
    ctx.effect(() => service.subscribe((value) => {
      console.log(value)
    }))
  },
}
```

若 `subscribe()` 不返回 disposer，先查询该服务的清理机制，别假设卸载会自动移除第三方回调。

## 定时器

定时器是名为 `timer` 的服务，不是全局。先经 `Service.listService` 查 `{"service": "timer"}` 再用；用前声明 `inject: ['timer']`。

```js
return {
  inject: ['timer'],
  apply(ctx) {
    ctx.timeout(() => console.log('done'), 300)          // 一次性
    ctx.interval(() => console.log('tick'), 1000)        // 周期
  },
}
```

错误形态：未声明 `inject: ['timer']` 就用 `ctx.timeout`；或直接 `setTimeout`（全局定时器不存在）。

## 监听事件

先查 Event Provider 确认事件名、参数顺序、返回值与 mode。

普通 emit 事件：

```js
return {
  apply(ctx) {
    ctx.on('some/event', (payload) => {
      console.log(payload)
    })
  },
}
```

Waterfall 事件最后一个参数是 `next`；除非有意中断下游，必须调用并返回它：

```js
return {
  apply(ctx) {
    ctx.on('some/waterfall', (payload, next) => {
      console.log(payload)
      return next()
    })
  },
}
```

## 注册动态模型工具

Host 侧用 `harness` 注册下一步模型就能调用的工具 —— 这是动态插件最常见的用途。先 `Builtin.listBuiltins` 确认 `harness` 签名，再 `Tool.listTools` 查重名。

参数与返回值必须 JSON 兼容；`execute` 持有业务结果，`render` 只决定模型/界面看到的文本。注册必须属于当前插件 Fiber —— stop/update 自动移除：

```js
return {
  apply(ctx) {
    harness.registerTool(ctx, harness.defineTool({
      name: 'workspace_digest',
      description: '摘要当前会话工作区：文件数与最近修改的三个文件名。',
      parameters: {},
      output: { schema: { type: 'string' }, render(_args, value) { return [{ type: 'text', text: value }] } },
      async execute() {
        const fs = ctx.get('fs')
        if (fs === undefined) return 'fs 服务不可用'
        const target = await fs.resolve('.')
        const entries = await fs.listDir(target)
        return `${entries.length} 个条目`
      },
    }))
  },
}
```

（服务方法签名只是示意 —— 实际以 `cordis_inspect_query` 查到的 `fs` 服务契约为准。）

## 活数据纪律

服务实例、事件负载、会话对象等 DSH/Cordis 对象是内部活数据：

- 不要对它们（或其子对象）用 `JSON.stringify` / `structuredClone`；
- 不要递归枚举、整体拷贝或整对象展示；
- 不要把 Host 对象放进插件的长生命周期状态或工具返回值。

只读当前功能需要的叶子字段，提取出最小的字符串/数字/布尔值再构造自有 JSON。

## 版本指针与修复

- 插件 = `pluginId` 标识的稳定实例；Package = `packageId` 标识的不可变代码版本；每次激活尝试有自己的 `pluginRunId`。
- `currentPackageId` 是最近成功的版本（不代表正在运行）；`nextPackageId` 是激活中或最近失败的目标版本。

`cordis_run` 的 mode 选择：

| 当前状态 | 目标 | mode |
| --- | --- | --- |
| 无 current | 该插件任意 Package | `run` |
| 有 current | 同一个 Package | `run` |
| 有 current | 另一个 Package | `update` |
| update 失败 | `nextPackageId` | `update` 重试 |
| update 失败 | `currentPackageId` | `run` 回滚 |

本部署无审批：没有 `awaiting-approval` 状态，也没有授权记录（grant）的概念。技术失败后的修复：

1. `cordis_inspect_self(pluginId, packageId)` 读失败版本的源码与确切诊断；
2. 错误涉及未知能力时，重新 list/query 对应 Provider；
3. 在同一插件下 define 新 Package（旧的不可变，不要想覆盖）；
4. 用新 `packageId` 与正确 mode 重新 run。

update 失败不会自动恢复旧版本；需要恢复就显式对 current 执行 `run`。

## 修改 @pluginId

用户用 `@pluginId` 点名目标时，不要另建插件。注入上下文只含身份、版本指针与默认基线 Package，不含源码。流程：

1. `cordis_inspect_self(pluginId, packageId)` 读基线 Package；
2. 只改目标代码，保住不需要动的部分；
3. `cordis_define` 用 `plugin.kind: 'existing'` 与原 `pluginId` 追加 Package；
4. 用返回的 `packageId`；已有 current 时通常用 `update` 激活。

引用不可用（已删除 / 属于别的会话 / 进程重启丢失）就直说，不要造同名替代品。

## 常见失败对照

| 失败 | 先查 |
| --- | --- |
| `service "x" is not declared` | 代码用了 `ctx.x` 却没在插件对象上声明 `inject: ['x']` —— 改 `ctx.get('x')` + 缺失判断，或声明真硬依赖 |
| `cannot get property "timer" without inject` | 查 timer 服务并声明 `inject: ['timer']` |
| 语法解析失败 | 是否用了 TS 类型 / JSX / import / 不可用全局；错误信息带出错行与提示 |
| `xxx is not available in the dynamic package sandbox` | 陷阱重定向：require → ctx 服务；定时器 → timer 服务；fetch → web 服务 |
| 插件 waiting | `inject` 声明的服务尚未就绪 —— 看返回值 `host.waitingFor`；若服务在基座本就不存在，改用 `ctx.get` 容缺 |
| 工具没出现在下一步 | 是否用了 `harness.registerTool(ctx, …)`；注册是否在 `apply()` 内（Fiber 归属）；名字是否与现有工具冲突 |
| update 失败 | current/next 语义：修好 next 再 update，或对 current 执行 run 回滚 |

## 本部署不可用的能力（索引）

上游完整技能里的以下章节在本部署**不适用**，未来若接入 WebView 宿主再解锁：注册 Client UI（Slots / `slots.inject` / `tool.view.cordis` 卡片）、主题与样式（`Theme.listTokens` / `styles.insert`）、Client→Host 私有 RPC（`harness.handle` / `host.call`）、客户端渲染诊断。`cordis_inspect_list` 目前只会返回 host 平面 Provider（Service / Event / Builtin / Tool）；若模型写了 `code.client`，包会停在无人响应的等待态 —— 修复方式是 define 一个只有 host 半边的新 Package。

## 喵仓路由备忘

- 外观/渲染诉求 → `appconfig/*.jsonc`（改完即时生效），别写插件；
- 「记住某事」→ 会话工作区 `workspace/memory/`；
- 临时能力（本进程/本会话）→ 本技能的动态插件；
- 可复用会话模式（别的会话也能挂）→ `editing-cordis-compositions` 技能的预设创作流程。
