---
name: editing-cordis-compositions
description: 创作、修改或验证 Cordis 组合 / Agent 预设前必读 —— 写新预设、增删插件行、判断某能力属于基座（host）还是单会话（preset）、验证自建预设能不能挂载、诊断「挂上了却没贡献」的行。喵仓部署：系统预设只读（访问一律走 roster），用户预设根可直接写，无沙箱无审批。
---

# 编辑 Cordis 组合（喵仓版）

这个运行时里，每种能力都是一个 `cordis.yml` 插件行。没有另外的配置语言：改变一个 agent 能做什么 = 改变为它组装了哪些行。本技能教的是喵仓部署（安卓 App 内置运行时）下的组合编辑与 Agent 预设创作。

## 先记牢：本部署的读写边界

- **系统预设目录（`filesDir/dsh-presets/`）是只读纪律，而且读也被拒**：fs 工具的 deny 规则在路径解析处拦截，读写都会得到 `FS_PERMISSION_DENIED`。这不是故障，别绕。访问系统预设的唯一正道是 **roster 服务**（`list()` / `read(id)` / `copy(from, id, name)`），见下文。bash 工具不经 fs-local，`cat` 绝对路径也能读，但优先 roster —— 它返回的就是部署认账的真实路径。
- **用户预设根（`${DSH_HOME}/.agent-presets/<id>/`，即 `filesDir/.dsh/.agent-presets/`）可读可写可删**：本部署没有文件沙箱、没有升级审批流。用 write/edit 工具直接写绝对路径即可，不需要（也不存在）`sandbox_permissions` 之类的东西。
- **基座组合（部署自己的 cordis.yml）不可改**：它是 APK 播种的 host 平面文件，在模型面前同样只读。预设解决不了的能力缺口，向用户说明，别试图改基座绕过去。

双目录纪律与喵仓其它动态配置同构（学一次就通用）：`config-defaults/`（只读模板）↔ `appconfig/`（可改副本）≈ `dsh-presets/`（系统预设，只读）↔ `.agent-presets/`（用户预设，可写）。

## 先判断平面

两个平面，判断标准不是「感觉上是否和 agent 有关」，而是**是否必须跨会话共享**。

**HOST 组合**：注册表本体（`tools`、`systemPrompt`、`agents`、`agent-loop`、`sessions`）、一切跨会话的东西（持久化、会话查询、存储、设置、凭证）、模型路由、subagent 注册表及其驱动。整个进程一份。

**AGENT PRESET**：单个会话贡献给那些注册表的东西：工具行、prompt 节、压缩策略。每会话一份，随会话挂载、随会话销毁。

**「服务有 agent 平面之外的消费者」就不能搬进预设。** `subagents` 是标准反例：注册表要回答跨会话查询，每会话一份副本既让 host 行永远等不到服务，又会在第二个会话上撞名（provider 名只能注册一次）。预设贡献的是**委派工具**；注册表和驱动留在 host。

预设是一个目录，装一个 `agent.cordis.yml`，旁边可选一个 `preset.yml` 放展示元数据（`name` / `description` / `order`）。元数据要写：没有它，预设在每个选择器里只会显示裸目录名。喵仓里它直接显示在 App「工作设置 → Agent 预设」的卡片上。

## roster 服务：预设的注册、读取、复制与验证

`agentPresets` 服务负责发现、创作与挂载。它不在模型工具清单里 —— 用动态插件给自己开一个探针工具来访问（流程：`cordis_inspect_list` → `cordis_define` 定义探针 → `cordis_run` 同步激活 → 下一步就能调自己的工具 → 用完 `cordis_undefine` 删掉探针）。

写代码前先查真实签名：`cordis_inspect_query`（platform `host`、provider `Service`、method `listService`、input `{"service": "agentPresets"}`）。本技能依赖的方法：

- `list()` —— 每个预设的 `id`、`trust`（系统预设 `system`、自建 `user`）、组合文件绝对 `path`。每次调用都重扫根目录，运行中新建的预设立即可见；定位任何组合都用它，别猜路径。
- `read(id)` —— 读一个预设的组合原文，不需要文件工具，也不受 deny 影响。
- `copy(from, id, name?)` —— **唯一的落盘写通道**（见下），整目录复制进可写根并返回落点。
- `standingKeyFor(id)` —— 挂载验证一个预设（见「验证改动」）。

探针插件完整示例（纯 JavaScript 函数体，经 `code.host` 提交）：

```js
return {
  inject: ['agentPresets', 'tools'],
  apply(ctx) {
    harness.registerTool(ctx, harness.defineTool({
      name: 'preset_check',
      description: '按 id 挂载验证一个 Agent 预设，返回 OK 或失败原因。',
      parameters: { id: { type: 'string', required: true } },
      output: { schema: { type: 'string' }, render(_args, value) { return [{ type: 'text', text: value }] } },
      async execute(args) {
        try {
          await ctx.agentPresets.standingKeyFor(args.id)
          return 'mounted OK'
        } catch (error) {
          return error.message
        }
      },
    }))
  },
}
```

验证完就用 `cordis_undefine` 删掉探针 —— 它是临时工具，不是要留下的能力。

## 创作一个预设

1. **从副本开始。** `copy(from, id, name)` 把整个预设目录复制进用户根 —— 组合、元数据、skills/ 全带走。id 必须匹配 `[a-z0-9][a-z0-9-]*`（会成为目录名），与任何已有 id 冲突会被拒绝，复制失败整体回滚；副本的 `preset.yml` 保留 description、丢掉 name 和 order。优先用它而不是 bash `cp`：落点一定是本部署的可写根，副本的可挂载性与原件完全一致。`resolve(id)` / `list()` 会给出副本的真实落点路径 —— 后续编辑以那个路径为准，不要猜。`meow-standard` 是全家桶，是最常用的复制源。
2. **改副本的 `preset.yml`**：写 `description`（App 卡片直接显示它），`name` 没在 copy 时传就补上。
3. **逐行编辑 `agent.cordis.yml`**，守住平面规则与 realm 规则（下两节）。用 write/edit 工具 + `resolve()` 给出的绝对路径直接写。
4. **挂载验证**（见「验证改动」）。
5. **让用户开新会话验收**：预设决定工具 schema 与 prompt 节，只有真会话能证明组合产出的 agent 长什么样。喵仓语义：**新模式只对新会话生效**，会话开始即固定，运行中的会话不热切。用户在 App「工作设置 → Agent 预设」里设默认或新建会话时选择。

从零手写的组合通常会漏 group realm 或消费者行；从副本改起，起点就是可加载的。

## 最容易翻车的一条

**提供服务（provide）的行不许裸放在预设里。** 注册服务而不套 `isolate` realm 会把它发布进进程全局 —— 第二个挂载该预设的会话就撞名，挂载审计会直接拒绝而不是让冲突晚点爆。

一行是否提供服务，从包名看不出来，部署里也没有包 README。看活运行时：`cordis_inspect_query`（provider `Service`、method `listService`、不带 input）列出全部服务及其归属 fiber —— 某服务归属的 fiber 不是你加的那一行，说明那一行是消费者而非提供者。不在当前组合里的行，挂载验证后读拒绝信息，它会点名问题服务。

预设真正独占某服务时，把**提供者与所有够得着它的消费者**包进同一个带 `isolate` 的组：

```yaml
- id: my-group
  name: cordis:group
  group: true
  isolate:
    myService: true
  config:
    - id: my-service
      name: '@deepseek-ai/dsh-xxx'
```

`true` = 每个挂载会话私有 realm。换成字符串标签是把多个子树并进一个共享 realm —— `provide()` 在第二次注册时照样抛错，所以标签不是预设要的东西。

消费者留在组外会解析到基座（host）的注册表 —— 预设没填充它，该行等于没写。挂载验证会把这种情况报成「从未激活的行」。

realm 只用于预设独占的服务，不是每个组都要：预设只是消费的 host 能力必须留在 realm 外，否则那行解析不到它（`tool-bash` / `tool-jobs` / `tool-goal` 在 meow-standard 里就是裸放的）。把消费者行单独包进自己的 realm，和把它漏在提供者的 realm 外，是同一种错误。

## 验证改动

**`standingKeyFor(id)` 是标准检查。** 它真实组装预设的插件子树 —— 与会话启动的挂载只差一个 agent —— 并拦住四种失败：

- 包解析不到（`Cannot find package …`）；
- 配置非法（`invalid config: $.<field> missing required value`）；
- 有行从未激活（`N row(s) did not activate: <id>: waiting for <service>`）；
- 服务发布进了全局 realm —— 部署没有的服务名落进 root realm，审计拒绝：`row(s) published process-global service(s) [<name>]; …`（预设自己忘了 realm 就是这个形态）；部署已有的服务名则在审计前就撞名：`service "<name>" has been registered at <Owner>`。两种都会点名问题服务。

正常返回即挂载成功。**做完后再跑一次，不要每改一行跑一次**：成功的挂载会装上常驻 generation 活到进程退出，失败的会销毁子树不留痕迹。

**别把名单的 `broken` 字段当验证。** `list()` 里的 `broken` 只来自形状检查（文件能解析、行有名），上面四种失败它全拦不住 —— 它抓的是损坏的文件，不是不可用的组合。

`cordis_inspect_*` 报告的是**当前会话**的组合，确认不了你新建的预设将来长什么样。挂载验证通过后，请用户开一个真会话确认工具清单。

动态插件（cordis_define/run）对活运行时求值、进程重启即消失 —— 用来探路，别用来交付能力；能力属于组合文件。

## 哪些东西不许搬进预设

`agent-loop` 只许注册一个 agent 工厂，第二个就抛错。注册表本体拥有按会话分层的能力，自身不能按会话化。会话持久化必须留在 host 侧，否则会话列表碎裂。本部署没有 sandbox/approval 行，但边界等价：**预设的特权 = 它点名的插件**，所以「预设放松自己的约束」这类行（例如往进程里塞全局服务的行）本来就被挂载审计挡住。

## 喵仓补充路由

- **外观 / 渲染诉求**（Markdown 样式、主题色、聊天背景、代码块/公式/Mermaid 外观）：不进预设、不进组合 —— 改 `appconfig/*.jsonc`（热更即时生效），见基座 persona 的外观路由段。
- **记忆**：写入会话工作区的 `workspace/memory/`。
- **临时能力**（本进程、本会话有用就够）：动态插件，用 `cordis-plugin-development` 技能。
- **可复用的会话模式**（要能被别的会话挂载）：本技能的预设创作流程。
