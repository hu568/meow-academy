# 🐾 DSH 补完计划会议笔记

> 日期：2026-08-29 · 主题：给喵仓加「创造模式」（Agent Preset），让用户（和 AI）自定义出更多 Agent 模式
> 一句话总结：**不走轻量自造模式格式，直接接入 DSH 原生 agent-presets 体系——闭包补 5+4 个包、cordis.yml 加 1 行、meow-jsonrpc 抄官方 composeAgent 约 60 行，缺口比预想小得多。**
> 状态：会议结论（直接做完整版），细化计划文档另立，本文为会议讨论沉淀。

---

## 0. 背景：问题是什么

- 喵仓内置 agent 目前是**手写 cordis.yml 组装**（`android-app/runtime-assets/dsh/cordis.yml`），基于官方 `examples/jsonrpc-agent` 示例裁剪 + 自定义 meow-jsonrpc 插件，agent 本体 = `dsh-agent-spine-demo` + 喵喵老师 persona。
- DSH 本身有四个内置「模式」（Agent 预设）：标准 / PTC / 极简 / 创造（`dsh/apps/cli/config/agent-presets/` 下 `standard|code|minimal|cordis` 四目录），喵仓**没有挂载 preset 体系**，四个模式一个都没在用。
- 目标：让用户能在喵仓里自定义 Agent 模式（persona、工具集、能力组合），并复刻 DSH「创造模式」——一个能编写其它 Agent 的 Agent。

---

## 1. 机制摸底：DSH 的 preset 体系怎么运作

### 1.1 preset 是什么

一个 preset = **一个目录**：`preset.yml`（name/description/order 元数据）+ `agent.cordis.yml`（插件组合）+ 可选附属文件（skills/、本地 JS 插件，相对路径 import 随目录走）。

- 内置四预设定义：`dsh/apps/cli/config/agent-presets/{standard,code,minimal,cordis}/`
- 用户预设根：`${DSH_HOME:-$HOME/.dsh}/.agent-presets/<id>/`（`agent-presets/src/discovery.ts:41` `USER_PRESET_DIR = '.agent-presets'`）

### 1.2 关键代码锚点（fork 内路径）

| 机制 | 位置 | 要点 |
|---|---|---|
| `CreateAgentOptions` | `packages/core/agent/src/index.ts:80` | `meta.agentPreset` 字段已预留；`setup(agentCtx)` 在发布前组合会话作用域（scoped 工具/prompt sections/restrict），失败整体回滚 |
| `AgentOptions` | `packages/core/agent/src/runtime-types.ts:24` | 按会话只有 provider/model/maxTokens；**persona 归 system-prompt sections**，不走 agentOptions |
| `tools.restrict({allow/deny})` | `packages/core/tools/src/index.ts:1071` | 只能在 `agent.ctx`（会话 scope）调用——按会话裁工具的现成 API |
| `mountPreset(agentCtx, preset)` | `packages/preset/agent-presets/src/mount.ts:332` | 把 preset 组合以 `Include` 子树挂到 agent 作用域，随会话销毁；bare specifier 从 harness 自身 node_modules 解析（**preset 能引用什么包 = 闭包里有什么**）；审计拒绝向 root realm 泄漏服务 |
| `resolveSessionPreset()` | `packages/preset/agent-presets/src/session.ts:48` | 从**事件日志**取会话所用 preset（header 只记创建时值）：空白期切换过 preset 的会话，resume 要恢复到新组合 |
| `AgentPresets` 服务 Config | `packages/preset/agent-presets/src/preset.ts:52` | `default`（**必填**，缺失 fail loud）+ `roots: [{path, trust}]` + `includeUserRoot`（追加用户根） |
| **官方集成配方** | `packages/host/apiproxy/src/api-proxy.ts:1168` `composeAgent` | create 前先 `presets.resolve()`（header 才能快照到）→ setup 里 `installSelection` + `presets.mount()`；resume 从日志 `resolveSessionPreset` → 同一 setup |

### 1.3 官方 composeAgent 配方（meow-jsonrpc 照抄这段）

```ts
async function composeAgent(presetId) {
  const presets = ctx.get('agentPresets')        // roster 服务（host 平面行）
  if (presets === undefined) return { setup: installSelection }  // 无 roster = 行为同旧
  const resolvedId = (await presets.resolve(presetId)).id
  return {
    agentPreset: resolvedId,                     // 记入 session header
    setup: async (agentCtx) => {
      installSelection(agentCtx)                 // meow 对应 installModelSelection
      await presets.mount(agentCtx, resolvedId)
    },
  }
}
```

- 喵仓现有扩展点：`meow-extensions/meow-jsonrpc.js:295` 的 `setup(agentCtx)` 里已挂 `installModelSelection`，同一个位置加 `presets.mount` 即可。
- 空白会话切换：记 `agent-preset/selected` 事件 + 非空白会话校验 preset 未变（`assertPresetUnchanged` 语义）。

### 1.4 preset 能力边界（skill 里写死的纪律）

**不能动 host 平面**：agent-loop 工厂（第二个就 throw）、注册表本体、会话持久化、模型路由、subagent registry 后端、sandbox/approval 行——「preset 的特权 = 它点名的插件」，放行 self-relax 就废了 confinement。preset 只装**每会话贡献**：工具行、persona/prompt sections、compaction 策略。

---

## 2. 原版创造模式到底能改多少

创造模式（cordis preset）= 标准全家桶 + 自指 Cordis 工具集 + 两份教学 skill + persona。可改范围分两层：

### 2.1 Preset 层（落盘、新会话可用）

| 可改项 | 内容 |
|---|---|
| 工具集 | 增删工具行、改工具配置（bash 超时/输出上限、subagent maxDepth/backgroundMode…） |
| persona 与提示词 | `dsh-persona` 文本（支持 `{{model}}`/`{{cwd}}` 变量）、agent-instructions |
| 上下文策略 | compaction-basic 参数、tool-result-pruner、command-compact |
| 能力开关 | plan-mode、goal、skills、web 搜索 |
| 子代理/工作流 | fork/spawn 后端、workflow、ralph、ask-user |
| 真·新插件代码 | preset 目录带附属文件，组合相对 import —— AI 写的本地 JS 插件随 preset 走 |
| 自带 skills | 新 preset 可附 skill 目录 |

### 2.2 运行时层（`cordis_*` 工具，进程内、重启即失）

- `cordis_inspect_list / inspect_query / inspect_self`：枚举运行时全部 Service/Event/Builtin/Slot/Theme/Tool 真实 schema（纪律：不许猜 API）
- `cordis_define / run / stop / undefine`：纯 JS 函数体定义**动态插件**并版本化激活——消费/提供服务、监听事件、**注册下一个模型步骤就能调用的新工具**、Client UI（Slots/主题，喵仓暂无宿主）；run 带审批流
- roster 服务（`ctx.agentPresets`）：`list / read / copy / resolve / standingKeyFor`——`copy()` 复制系统 preset 到用户根；**`standingKeyFor()` 真实挂载验证**（AI 写完组合自检「能不能挂上」，报错精确到行）——创造模式闭环的关键
- `cordis_mount` 对活运行时执行模型写的 JS。组合文件头 TRUST 注释原话：「把这个 preset 上的会话当 shell 权限对待」

### 2.3 随附教学材料（移植时要中文化改写）

- `cordis-plugin-development/SKILL.md`（421 行）：动态插件开发全流程
- `editing-cordis-compositions/SKILL.md`（166 行）：组合编辑 / preset 创作 / isolate realm 规则 / 挂载验证

---

## 3. 安卓可用性盘点

### 3.1 闭包实测（2026-08-29，对 `.tmp/dsh-closure.tar.gz` 8/23 版数文件）

| 包 | 条目数 | 状态 |
|---|---|---|
| `dsh-skill` / `dsh-skill-filesystem` / `dsh-tool-skill` | 11 / 20 / 11 | ✅ 已打包，休眠（spine `skills.enabled: false` + 两行未挂） |
| `dsh-subagent`（核心注册表） | 45 | ✅ 已打包，休眠；**工具行 `tool-subagent` 与驱动 `subagent-spawn-in-process` 不在 manifest** |
| `dsh-jobs` / `dsh-tool-goal` | 17 / 13 | ✅ 已打包，休眠 |
| `@vscode/ripgrep` | 0 | ❌ 不在闭包（fs-search 的硬依赖） |

结论：**「高级能力」多数已打包只是没挂载**——skills 是纯配置翻转（skill-filesystem 纯 node:fs，发现根 `<cwd>/.dsh/skills`、`<dshHome>/skills` 全在 filesDir 私有目录，天然适配）；subagent 要补 2 个包。

### 3.2 可用性分级

- ✅ **可用**（纯 JS，包在闭包内或易加）：preset 挂载本体（`cordis-plugin-include` 已在 manifest，缺 `dsh-agent-presets`）、工具行全家（bash/fs/str-replace-editor/web/todo/skill/jobs/goal）、persona、agent-instructions、plan-mode、compaction 全家、**tool-cordis 的 Host 半边**（动态工具/服务/事件）、subagent 进程内驱动（不依赖 node-pty）
- ⚠️ **受限**：tool-cordis Client 半边（Slots/主题/React UI 无宿主 → 未来 WebView 补齐 DSH Web 前端后解锁，preset 不用改设计）；`tool-ask-user`（需 App 问答 UI 通道）；`tool-fs-search`（spawn 外部 rg 二进制，`search-core.ts:231`）
- ❌ **不移植**：`tool-pwsh`（无 PowerShell）；沙箱升级审批流（喵仓无沙箱 = **简化点**，AI 本就有 filesDir 写权限，用户 preset 目录直接可写）；codex/claude-code subagent provider bundles（需平台 CLI）

---

## 4. 方案讨论与决策

| 方案 | 内容 | 结论 |
|---|---|---|
| A 轻量 | 模式 = JSON 配置（persona + 工具 allow/deny + 模型绑定），meow-jsonrpc 用 `restrict`/scoped section 应用，创造模式 = AI 改 JSON | ❌ 不做 |
| B 完整 | 接入 DSH 原生 agent-presets 体系，创造模式 = 移植 cordis 预设 | ✅ **直接做** |
| C 分期 | 先 A（格式对齐 preset 语义）后 B | ❌ 迁移麻烦，且工具经盘点并不缺 |

**决策理由**：
1. 盘点后发现「工具没那么缺」——skills/goal/jobs 已在闭包，缺的核心就是 agent-presets + tool-cordis 两个包；
2. 方案 A 的模式文件格式是自造的，将来升级 B 要迁移；直接 B 与上游同构，DSH 升级 preset 机制跟着走；
3. 模式定义（用户 preset 双目录）与喵仓既有 config-defaults/appconfig JSONC 模式**同构**（见 §6），认知负担低。

**已定细节**：
- 切换粒度：**新模式只对新会话生效**（DSH 语义：会话开始即固定，空白会话可换），不做运行中会话热切；
- 创造模式 AI 权限：无沙箱简化——没有「写 preset 根要 sandbox 升级审批」的流程，AI 直接写 `~/.agent-presets/`；deny 边界照旧（credentials/datastore/config-defaults）；
- persona：系统 preset 带 persona 行（preset persona shadow 部署默认），基座 agent-spine 的喵喵老师 persona 保留不动；
- 外观诉求路由：persona/skill 里写明「外观/渲染改 `appconfig/*.jsonc`」，别让 AI 往 cordis.yml 塞它改不了的 UI 诉求。

---

## 5. 精确补齐清单（直接做完整版）

### A. 闭包新增（deploy/meow-runtime/package.json → 重打闭包/runtime.bin）

**必加 5**：`dsh-agent-presets`（+新外部依赖 js-yaml）、`dsh-tool-cordis`（零依赖）、`dsh-persona`、`dsh-subagent-spawn-in-process`、`dsh-plan-mode`（+zod@4）。
**按需 4**：`dsh-tool-subagent`、`dsh-tool-subagent-control`、`dsh-command-compact`、`dsh-tool-ask-user`（先包后接 App UI）。
**裁 2**：`tool-fs-search`（依赖 @vscode/ripgrep 会连带装各平台 rg 二进制，且 Android 无 prebuilt，除非先做 Termux rg 打包）；`tool-workflow`+`workflow-worker-thread`+`tool-ralph`（worker_threads 未真机验证）。
体积预估：纯 JS 小包，压缩后增量 ~1-2MB（基线 37MB 闭包 / 68MB runtime.bin）。

### B. cordis.yml —— 只加一行

```yaml
- id: agent-presets
  name: '@deepseek-ai/dsh-agent-presets'
  config:
    default: meow-standard            # 必填，缺失 fail loud
    roots:
      - path: !!js <filesDir 下系统 preset 目录>
        trust: system
    includeUserRoot: true             # 用户根 = ${DSH_HOME}/.agent-presets/
```

基座（喵喵老师）组合**不动**——「补齐」发生在系统 preset 的组合文件里，不是往基座堆行；用户 preset 引用什么包，闭包里有就行。注意 `!!js` 在 ESM 作用域无 `require`（老坑，路径用字符串拼接）。

### C. 系统 preset 播种 ×2（主要文本工作量）

1. **`meow-standard`**（默认）：裁剪版标准组合——bash/fs/str-replace-editor/web/todo/skill-filesystem+tool-skill/jobs/goal/plan-mode/compaction(basic+pruner+command-compact)/subagent 委派；裁 pwsh、fs-search、workflow/ralph、sandbox。persona 行带喵喵老师提示词。
2. **`meow-cordis`**（创造模式）：meow-standard 全部 + `tool-cordis` + 两个 skill 目录。中文化改四处：路径改 `${DSH_HOME}/.agent-presets/`、删整段沙箱升级流程、外观路由到 `appconfig/*.jsonc`、补 deny 边界说明。

播种复用 config-defaults 的 sync-token 模式；系统目录可加进 fs deny 只读（AI 可读可复制，不可改——与「shipped preset 不可改，改副本」纪律一致）。

### D. meow-jsonrpc.js —— 约 40-60 行

照 `api-proxy.ts:1168` `composeAgent` 抄：create 前 resolve → `meta.agentPreset` → setup 挂载；resume 从日志 resolveSessionPreset；新增 `presets/list`、`session/setPreset`（仅空白）；错误映射 `UnknownPresetError` / `PresetMountError`（自带逐行原因）/ conflict → 可读 jsonrpc error。

### E. App 侧（Kotlin）

1. 模式管理 UI（设置页）：列表（内置/自定义分组）+ 设默认 + 删除自定义（roster 有 `copy()`；delete 走文件删除）；
2. 新会话带 presetId（ChatViewModel → create params）；会话抽屉标注会话所用模式；
3. 会话创建失败（mount 失败）的聊天内可读报错透传；
4. 确认 `DshProcessLauncher` 是否注入 `DSH_HOME`（没有则加，指向 filesDir 下）。

### F. 验证

闭包 → Termux 打包 → `assembleDebug`（对比体积）→ 真机：默认模式工具清单 → 创造模式里让 AI copy preset 改组合 → `standingKeyFor` 验证 → 新会话跑新 preset → 杀进程恢复会话（resume 挂载正确）。

---

## 6. 与喵仓既有动态化架构的关系

- **用户 preset 双目录 = appconfig 模式同构**：系统 preset（只读播种，sync-token 刷新）↔ `config-defaults/`；用户 preset（AI/用户可写）↔ `appconfig/`；「shipped 不可改、改副本」↔ config-defaults 的 deny 纪律。AI 学一次就通用。
- **外观定制**：appconfig 已统一 JSONC（`stripJsonc` + 深合并 + null 回退，2026-08-24 落地，见动态化笔记 §11）——创造模式的「AI 帮用户改外观」价值由喵仓自有通道承接，不依赖 DSH Client 半边。
- **WebView 补齐 DSH Web 前端（规划中）**：就位后 tool-cordis 的 `code.client` / Slots / 主题覆盖自然解锁，**已写好的用户 preset 不用迁移**——Client 半边缺失是「暂缺宿主」，不是「永远没有」。
- 顺带：`meow-dynamic-architecture-note.md` §10 提到的 markdown-config **仍是旧 JS/Rhino 描述**，AGENTS.md 的 MarkdownConfig 段同理——已迁 JSONC，待同步。

---

## 7. 下一步行动（先讨论定案，计划文档另立）

- [ ] 立细化计划文档（对齐 plan-phase1 格式，按 §5 A-F 拆任务）
- [ ] manifest 加包 + pnpm-workspace/lockfile importer → 重打闭包 → 实测体积
- [ ] `meow-standard` / `meow-cordis` / `meow-minimal` / `meow-code` 四个 preset 编写 + 两个 skill 中文化改写
- [ ] meow-jsonrpc `composeAgent` 移植 + presets/list / session/setPreset + 错误映射
- [ ] 确认 DshProcessLauncher 的 DSH_HOME 注入
- [ ] App 侧模式管理 UI + 会话创建参数 + 错误呈现
- [ ] 真机验收清单（§5 F）

## 8. PTC 模式评估（2026-08-30 追加）

### 8.1 机制：和其它三个模式比，「特别」在哪

PTC = 标准模式 + **一行** `tool-presentation`（`@deepseek-ai/dsh-agent-tool-presentation`，全包 104 行）。变的不是工具集，是**模型看到的工具形态**：

- `native`：常规，每工具一个 schema（喵仓现状）
- `code`：模型只见一个 `run_code` 工具 + 系统提示里由工具 schema 生成的 **TypeScript SDK 声明**；模型写 TS 程序组合调用工具
- `both`：两者并存（对学习 App 可能是更好的默认）

核心价值：「五次 LLM 往返变一次」——程序内循环/条件/`Promise.all` 并行，工具调用经 message port 桥回宿主真实注册表，**中途不经过 LLM**。

它是四个预设里**唯一需要 host 平面新增服务**的：`dsh-code-runtime-worker-thread`（CLI 侧由 bundle patch 挂载：`packages/bundle/headless/cordis.patch.yml:25`）。

### 8.2 执行引擎

每个程序起一个**全新 worker 线程**（`node:worker_threads`）：模型的 TS 经 `node:module.stripTypeScriptTypes` 剥类型（**Node 内置 API，无 esbuild/二进制依赖**）→ 包成 async 函数体（`STRIP_WRAP`，位置保持）→ worker 里执行。资源控制（`code-runtime-worker-thread/src/index.ts:25`）：

| 控制项 | 默认 | 说明 |
|---|---|---|
| computeMs | 60s | 按 eventLoopUtilization 测**忙碌时间**——等慢工具不计费，热循环逃不掉 |
| maxWallMs | 600s | 挂钟兜底（等永不 resolve 的 promise 也会被杀） |
| maxOldGenerationSizeMb | **512** | worker 堆上限，超限杀 worker（**安卓要调低 ~128-192**） |
| maxOutputBytes | 64MB | 日志+返回值序列化上限 |

定位原话：「containment, not a security boundary」——模型代码 bash 同等信任，只是空环境 + 预算 + 可终止（同步死循环也能停）。与喵仓信任模型一致。

### 8.3 安卓可行性（本机实测确认，见 §9）

- 缺 3 个纯 JS 包、零原生依赖：`dsh-agent-tool-presentation`（104 行）、`dsh-code-runtime`（契约）、`dsh-code-runtime-worker-thread`（~2000 行）；`run_code` 工具定义/SDK 生成/`presentAs` **已在闭包内**（`dsh-tools` 的 `code-mode.ts`）
- 真机已验证：node v26.4.0（≥22.13 需求满足）、worker_threads 起停/通信/resourceLimits 正常 → **两个原风险点全部消除**
- 待做：worker 堆上限调低；`meow-code` preset（= meow-standard + selector 行 `mode: code`）；cordis.yml 挂 host 服务行 + selector

### 8.4 两条落地路径

| 路径 | 依赖 | 成本 |
|---|---|---|
| ① 全局开（不走 preset） | 无 | 3 包 + cordis.yml 2 行（host 服务 + selector 挂基座）→ 所有会话 PTC/both |
| ② 作为可选模式 | 创造模式的 agent-presets 集成 | ① + `meow-code` preset 文件 → 会话级选择 |

排序建议：先做①的真机冒烟（已完成 §9）消掉不确定性；②随创造模式同一批重打闭包顺路带上。

---

## 9. 真机实测记录（2026-08-30 adb，设备 7450455c）

> 经 Windows adb（`/mnt/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe`）执行；WSL 侧 adb server 起不来（镜像网络下 5037 端口冲突），USB 设备也只在 Windows 侧可见。

### 9.1 node 版本与 worker_threads（PTC 前置验证 ✅）

- **node v26.4.0**（`linker64` + `LD_LIBRARY_PATH=meow-runtime/lib` 加载）
- `node:module.stripTypeScriptTypes` = function（PTC 剥类型 API 可用）
- worker_threads 冒烟通过：`new Worker(src, {eval:true, resourceLimits:{maxOldGenerationSizeMb:128}})` 起停/回传消息/terminate 全正常
- 注意：直跑 node 会报 OpenSSL config 错误（读 Termux 路径被拒），须按 DshProcessLauncher 同款注入 `OPENSSL_CONF` + `NODE_EXTRA_CA_CERTS`（指向 `meow-runtime/etc/tls/`）

### 9.2 App 终端 `node -v` 失败：根因 = SELinux exec 限制，不是 bug

- run-as 域（不受 targetSdk W^X 限制）直接 exec node 成功进动态链接器（缺 `LD_LIBRARY_PATH` 时报 `libz.so.1 not found`）→ 证明二进制本身完好
- App 自身是 **untrusted_app + targetSdk 34**：Android 对 targetSdk ≥ 29 的应用禁止 exec 私有数据文件（W^X 限制），即 AGENTS.md 记录的坑——「只能 exec 系统 ELF（linker64 / /system/bin/sh）」
- 所以终端里 `node -v` = bash 尝试 exec `meow-runtime/bin/node`（私有 ELF）→ EACCES Permission denied；DSH 本体没这个问题是因为 App 和 bash-local 都走 `[linker64, node/bash, ...]` 链
- **可选修复**（对齐 `build-runtime.sh` 里 bash 的 wrapper 方案）：真 ELF 挪到 `lib/node.bin`，`bin/node` 改 wrapper 脚本（`#!/system/bin/sh` + `exec /system/bin/linker64 .../lib/node.bin "$@"`，shebang 解释器是系统 sh 所以脚本可执行）；同步改 launcher 的 node 路径 env。改完终端里可直接敲 `node` **（⚠️ 2026-08-30 修正：App 域 wrapper 不可 exec，此路线不可行——见 §9.5，最终走 BASH_FUNC 导出函数）**
- 无障碍替代：终端里手动 `/system/bin/linker64 $HOME/meow-runtime/bin/node -v`（env 已继承 LD_LIBRARY_PATH 时可用）

### 9.3 terminal-bash 就绪检测复现（极简模式风险点验证 ✅ 2026-08-30）

按 `terminal-bash` 的真实协议 1:1 复现（`pty.spawn` + bash 方言 env 注入 `PS1='dsh> '` / `PROMPT_COMMAND=printf "\033]133;D;%s\007"…`，argv `--noprofile --norc -i`，等首个 OSC `133;D;` 标记 + `dsh> `，再执行命令等第二个标记）：

| spawn 形态 | 结果 | 就绪耗时 |
|---|---|---|
| ① `linker64` + `lib/bash.bin` 直载（推荐配置） | ✅ 全握手通过 | **33ms** |
| ② `bin/bash` wrapper 脚本（shebang → 系统 sh → linker64） | ✅ 全握手通过 | **35ms** |

> ⚠️ 2026-08-30 修正：本节两种形态都在 **run-as 域**验证（不受 targetSdk W^X 限制）——形态② 的「通过」对 App 域是**假阳性**，App 域 exec wrapper 报 `bad interpreter: Permission denied`（见 §9.5）；形态① linker64 是系统 ELF，App 域合法，仍是 terminal-bash shellPath 的唯一可行形态。

- 官方 `node-pty`（注入 Android prebuild 后）经 DSH 侧路径**首次真实跑通**：加载/起 PTY/数据流/终止全正常（此前只有 App 终端走 fork 包直连）
- 结论：极简模式最后的风险点消除；`terminal-bash` 在安卓可配置可用

### 9.4 附带发现：`bin/bash` wrapper 的 HOME 隐患（潜在 bug）

- wrapper 内容 `exec /system/bin/linker64 "$HOME/meow-runtime/lib/bash.bin"` 依赖 `$HOME`；但 launcher 注入 `HOME = filesDir/workspace`（`DshProcessLauncher.kt:67`）→ `$HOME/meow-runtime/...` 解析到 `files/workspace/meow-runtime/...`，**不存在**
- 真实 App 环境下 App 终端里敲 `bash`（走 PATH → bin/bash wrapper）应报 No such file or directory；今天没暴露是因为 App 终端与 bash-local 都走 `DSH_BASH_BIN`（`lib/bash.bin` 绝对路径），无人消费 wrapper
- 测试中以 `HOME=filesDir` 验证 wrapper 链路本身是通的 → 修复方向：wrapper 改用绝对路径（或 launcher 增注 `MEOW_RUNTIME_DIR` env 供 wrapper 引用）；与「bin/node wrapper」（§9.2）可一并处理。**→ 修复计划已立：`plan/plan-bugfix.md`（B1/B2 wrapper 体系 + B4 persona，B3 文档同步已完成）**

### 9.5 B1/B2 实施修正：run-as 假阳性与最终实现（2026-08-30 实施后回填）

**方案变更**：§9.2/§9.3 里「shebang 解释器是系统 sh，所以 wrapper 脚本可执行」的前提是**假阳性**——§9.3 两种形态都在 run-as 域验证，不受 targetSdk W^X 限制。真机 **App 域**（untrusted_app + targetSdk 34）实测：**exec app 数据文件一律 EACCES，shebang wrapper 脚本也不例外**（`bad interpreter: Permission denied`；DSH 模型在 bash 工具内首次复现并如实报告，App 终端重定向复核实锤）。与 phase1 踩坑记录「不能 exec 脚本」一致——W^X 对脚本和 ELF 一视同仁。

**最终实现（三层，真机验证全绿）**：

| 层 | 改动 |
|---|---|
| `build-runtime.sh` | node 真 ELF 挪 `lib/node.bin`；`bin/bash`、`bin/node` 均为 wrapper，`${MEOW_RUNTIME_DIR:-$HOME/meow-runtime}` 定位（B1 修复；wrapper 本体保留供 run-as/Termux 手测域） |
| `DshProcessLauncher.kt` | 注入 `MEOW_RUNTIME_DIR`；node 命令/`DSH_NODE_BIN` 改 `lib/node.bin`；**新增 `BASH_FUNC_node%%`/`BASH_FUNC_bash%%` 导出函数**——App 域内 PATH 直跑的实际通道（bash 启动自动导入 `BASH_FUNC_*`；函数体**不用 `exec`**，fork 子进程跑 linker64 → lib 真 ELF） |
| `RuntimeExtractor.kt` | chmod +x 与完整性检查同步到 `lib/node.bin` + bin wrapper 新布局（计划遗漏点，已补） |

- env 继承链核实两条：PTY bash 全量继承 terminal-host env；DSH bash 工具经 `childEnv(scrubbedParentEnv)`（scrub 只滤 KEY/PASSWORD/SECRET/TOKEN 与 `DSH_` 前缀，`BASH_FUNC_*`/`MEOW_RUNTIME_DIR` 均可通过）
- 真机验证：App 终端 `node -v` → v26.4.0、`bash --version` → GNU bash 5.3.9、嵌套 bash 正常；DSH 拉起/聊天正常；bash 工具内 `node -v` 直跑（无 linker64 绕道）；adb 七项全过；APK 94M 无膨胀
- 遗留约束：wrapper 在 App 域仍不可 exec（SELinux 常态）→ **terminal-bash 的 shellPath 只能走 §9.3 形态①（linker64 直载）**；`#!/usr/bin/env node` 类 shebang 场景在 App 域同样不可用
- 实施记录详见 `plan/plan-bugfix.md` §2.5

## 10. 极简模式评估（2026-08-30 追加）

### 10.1 机制：四个模式里唯一的「减法」

极简 = persona + **持久 PTY bash**（terminal 家族）+ `fs-local`/`str-replace-editor`，没有 compaction、todo、web、jobs、goal、subagent。组合仅三组：

```yaml
- id: persona          # dsh-persona
- id: persistent-shell # cordis:group
    pty:            '@deepseek-ai/dsh-terminal'            # PTY 会话管理（零依赖）
    terminal-bash:  '@deepseek-ai/dsh-terminal-bash'       # bash 方言后端（就绪检测/输出清洗）
    persistent-bash:'@deepseek-ai/dsh-tool-bash-persistent' # 模型工具行
    # terminal-pwsh / persistent-pwsh 两行：安卓不移植
- id: filesystem       # cordis:group
    fs-local / str-replace-editor   # 喵仓已挂基座
```

与其它模式的对比：标准=全家桶；PTC=工具**形态**变化（run_code）；创造=自指工具集；**极简=做减法，但把 bash 换成持久 PTY**。上游把持久 shell 当作极简底座：状态保留（cd/env/后台进程）、交互式程序可跑、不用每命令重新 spawn。

### 10.2 安卓可行性：底座已通，比预想顺

- **node-pty 的安卓问题已被现有构建解决**：`build-runtime.sh` 把 Android fork（`@mmmbuto/node-pty-android-arm64`）的 `pty.node` 复制进官方 `node-pty` 的 `prebuilds/android-arm64/`，而 DSH 的 PTY 走 `subprocess-local`（`nodePty.spawn`，`subprocess-local/src/index.ts:175`）——即 **DSH 的 PTY 原语在安卓上本就可跑**（与 App 真终端同一条 fork 的两个消费方）
- 需加 4 个纯 JS 包：`dsh-terminal`（零依赖）、`dsh-terminal-bash`（+`dsh-pwsh-local` 助手/`schemastery`）、`dsh-tool-bash-persistent`（仅 schemastery）、`dsh-pwsh-local`（纯 JS 助手，bash 方言只 import 常量）
- 配置要点：`terminal-bash` 的 `shellPath` 默认 `/bin/bash`（安卓没有）→ **必须显式配置**，两种形态均实测可用（§9.3）：① `shellPath=/system/bin/linker64` + `shellArgs=[<runtime>/lib/bash.bin, --noprofile, --norc, -i]`（推荐，无 HOME 依赖）；② `shellPath=<runtime>/bin/bash` wrapper（有 §9.3 的 HOME 隐患）；pwsh 两行不移植
- ~~风险点仅 1 个~~ → **已真机验证消除（§9.3）**：prompt 就绪检测（`CONTROLLED_PROMPT`/OSC 133;D 标记）在两条 spawn 形态下均 33/35ms 通过
- 说明：模型侧持久 bash 与 App 用户终端（terminal-host 的 PTY bash）是**两条独立链路**，互不干扰

### 10.3 价值与定位（喵仓视角）

1. **能力升级伪装成减法**：当前 bash-local 是一次性 + 60s 超时；持久 bash 保留状态、可跑交互/长任务——对模型是实打实的工具升级；
2. **preset 体系的首个验证载体**：组合最简单（2-3 行），适合在创造模式集成落地后第一个接入验证；
3. **长期可反哺基座**：若持久 bash 真机稳定，基座 bash-local 可考虑换成持久形态（独立决策，另议）；
4. 最小工具集 = 最可预测的 agent，教学场景有价值（观察最简 agent 的工作方式）。

### 10.4 接法

依赖创造模式的 preset 集成；`meow-minimal` preset = persona 行 + persistent-shell 组（仅 bash 行，`shellPath=/system/bin/linker64` + `shellArgs=[lib/bash.bin, …]`，见 §9.3）+ filesystem 组。pwsh 两行删除。**meow-jsonrpc 零改动**。

---

## 11. 四模式一图流（喵仓落地视角）

| 模式 | 本质 | 闭包新增 | 安卓风险 |
|---|---|---|---|
| 标准 | 全家桶组合 | 0（工具行已全在闭包） | 无 |
| PTC | 工具**形态**：run_code + TS SDK | 3 包（selector + code-runtime ×2） | ✅ 已实测消除（§9） |
| 极简 | 减法 + 持久 PTY bash | 4 包（terminal 家族） | ✅ 已实测消除（§9.3） |
| 创造 | 自指工具集 + preset 创作 | 5+4 包（§5） | 低（已裁 fs-search/workflow） |

---

> 📌 关联：`meow-dynamic-architecture-note.md`（JSONC 配置模式 / WebView 规划）、`docs/decision-dsh-agent.md`（DSH 组合架构）、`deploy/meow-runtime/package.json`（闭包清单）
> 🧭 本笔记对应仓库：github.com/hu568/meow-academy
