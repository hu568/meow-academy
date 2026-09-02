# design-memory-system.md — 喵仓记忆系统设计（需求对齐）

> 2026-09-02。需求来源：主人——在 Cherry Studio 记忆系统（已调查，见
> `docs/reference/cherry-studio-memory-system.md`）基础上设计喵仓自己的记忆系统。
> 本文档先对齐需求，后续设计/实施在此之上展开。

## 〇、背景

- **现状**：`.agents/memory/SOUL.md` 单文件管角色设定，经 meow-jsonrpc `{{soul}}`
  变量实时注入（plan-soul.md）；无主动记忆能力；基座 persona 有"记忆功能由后续版本
  提供"的占位文案。
- **目标**：借鉴 Cherry Studio 2.x 记忆系统，拆成两块：
  1. **角色设定**（Cherry 的 SOUL.md + USER.md 人格面）→ 多角色、可切换
  2. **长期记忆/跨会话记忆**（Cherry 的 `memory/` 设计：FACT.md + JOURNAL.jsonl）→ 全局共享

## 一、目录结构（已与主人对齐）

```
.agents/
├── memory/                          # 长期记忆（全局共享，沿用 Cherry Studio 设计）
│   ├── FACT.md                      # 长期事实知识：跨会话项目知识/技术决策（6+ 个月）
│   └── JOURNAL.jsonl                # 事件日志：一次性事件/会话笔记（append-only，仅专有工具可写）
└── personas/                        # 角色库：一个角色一个子文件夹
    ├── README.md                    # 角色库说明 + 技能索引（AI 可读，入口文档）
    ├── .personas-order              # 角色显示排序（前端拖拽持久化，JSON 数组存角色 id 顺序）
    ├── skills/                      # 内置技能（不主动加载，仅 README 引用）
    │   └── soul-md-generator/       # 创建角色技能（改造自 .tmp/soul-md-generator.zip）
    │       ├── SKILL.md             # 技能说明：三件套创建流程
    │       └── references/          # 模板 + 示例
    ├── <角色id>/
    │   ├── persona.yml              # 角色元数据（前端展示用）：name/description
    │   ├── SOUL.md                  # 角色人格设定（纯净提示词文体，整文件进系统提示词）
    │   └── USER.md                  # 该角色专属的用户档案（随角色切换）
    ├── <角色id2>/
    │   ├── persona.yml
    │   ├── SOUL.md
    │   └── USER.md
    └── ...
```

- 文件夹名 = 角色 id（角色库与 memory/ 平级）
- 每角色一份专属 USER.md（描述使用该角色时的用户/主人偏好，切换角色随之切换）
- **persona.yml = 前端展示的"名字 + 简介"来源**（已确认：文件名 persona.yml，由内置 AI 管理）
  - 字段：`name`（展示名）、`description`（一句话简介）
  - **无 order 字段**——排序由 `.personas-order` 文件管理（前端长按拖拽 + 持久化，
    不做数字手改）
  - SOUL.md 保持纯净提示词文体，不塞 YAML front matter
  - 内置 AI 自主写/改（仿 preset 自动扫描，App 不硬编码列表）
- **内置技能 `personas/skills/soul-md-generator`**（已确认：改造 zip 为「创建完整角色三件套」）：
  - 访谈收集 → 生成 `persona.yml` + `SOUL.md` + `USER.md` 写入 `personas/<角色id>/`
  - **不主动注入/不进 skill 注册表**（不配 skill-filesystem roots），仅 `README.md` 引用，
    AI 需要时用 read 工具读 `SKILL.md` 按流程执行
  - **无 bootstrap 引导**：不主动弹引导对话，靠用户主动要求 / AI 读到 README 自行触发
- **README.md = 角色库入口文档**：说明目录结构、创建角色方法（引用技能）、管理方式
- **存量兼容**：现 `.agents/memory/SOUL.md` 需迁移为 `.agents/personas/<默认角色>/SOUL.md`

## 二、三层架构：注入模型

### 2.1 系统提示词组成

每次组装提示词时，由三个独立层拼接而成：

```
系统提示词 = agent预设（工具 + 行为模式）
           + 角色层（<soul> + <user>，开关 ON 时）
           + 记忆层（<facts> + 契约 + memory 工具，开关 ON 时）
```

```
┌─ 基座层（cordis.yml，进程级永久）
│   ├─ 环境说明四段（工作区/外观/安全边界）
│   └─ 服务注册表（bash/fs/llm/spine/subagent…）
│
├─ 角色层（可由会话开关关闭）
│   ├─ 开启时注入：
│   │   ├─ <soul>〈SOUL.md 内容，按会话 personaId 解析，首条消息快照〉
│   │   └─ <user>〈USER.md 内容，同角色，首条消息快照〉
│   └─ 关闭时：不注入任何人格内容（但通用 fs 工具仍可读写 SOUL.md/USER.md）
│
├─ 记忆层（可由会话开关关闭）
│   ├─ 开启时注入：
│   │   ├─ <facts>〈FACT.md 内容，全局共享，首条消息快照〉
│   │   ├─ 存储契约（记忆规则说明）
│   │   └─ memory 工具（update/append/search）
│   └─ 关闭时：不注入事实内容、规则、记忆工具（但通用 fs 工具仍可读写 FACT.md）
│
└─ agent 预设层（每会话挂载，纯能力面）
    └─ meow-standard / minimal / code / 用户自建
        └─ 工具行 + 模式组合（plan/goal/skill/job…）
```

### 2.2 两个开关的排列组合

| 角色开关 | 记忆开关 | 注入内容 | 场景 |
|---------|---------|---------|------|
| ON | ON | 完整：人格+记忆+memory 工具 | 默认完整模式 |
| ON | OFF | 只人格，无长期记忆 | 不需要跨会话回忆 |
| OFF | ON | 无人格，有记忆 | 纯工具人+记忆库 |
| OFF | OFF | 纯净 agent 预设 | 裸工具面 |

### 2.3 开关模型

- **作用域**：会话级别（每个会话独立控制，互不影响）
- **锁定时机**：首条消息后锁定（发出去就定死了）
- **锁定范围**：所有记忆注入内容（`<soul>`/`<user>`/`<facts>`）在首条消息时**固化快照**，后续消息 system prompt 前缀完全不变
- **KV 缓存理由**：DeepSeek 等 LLM API 的 KV cache 按 prompt **前缀匹配**命中。如果每次请求 system prompt 前缀一致，缓存命中→大幅降低延迟与成本；如果前缀每次变化（如实时读文件），缓存永不可命中，每次全量计算
- **默认状态**：两个开关默认 **ON**（默认功能启用）
- **存储**：Room Session 表新增字段
- **角色开关 OFF**：无需角色，角色选择器按钮灰掉（不弹窗），personaId 不绑定

## 三、角色机制

| 决策点 | 决定 |
|--------|------|
| 角色作用域 | **会话绑定角色**：Room Session 表加 `personaId` 字段 |
| 切换时机 | **首条消息后锁定**：首个 prompt 携带 personaId → 定死归属（仿 presetId 机制） |
| 内容快照 | 角色文件内容（SOUL.md/USER.md）在首条消息时**固化快照**，之后编辑文件不影响已锁定会话，只影响新会话 |
| 聊天页切换 | 新建会话（空会话首条消息前）可自由切换；已有消息的会话角色锁定，选择器显示锁定角色但不可切（灰掉/只读） |
| 角色显示 | 会话标题上方 + 会话页内显示当前角色名（来自 `personas/list` 返回的 name） |
| USER.md | 每角色一份专属档案，随角色切换 |
| 名字/简介来源 | `persona.yml`（name/description），由内置 AI 自主管理，自动扫描 |
| 默认角色 | 默认功能 ON，但种子 SOUL.md/USER.md 为**空白模板**（带注释说明），由 AI 自主填写 |
| 角色开关 OFF | 无需角色，角色选择器按钮灰掉（不弹窗），personaId 不绑定 |
| 排序 | **前端长按拖拽 + 持久化**（`.personas-order` 文件），不做数字手改 |
| 编辑工具 | **无专用角色编辑工具**——SOUL.md/USER.md 走通用 fs 工具，角色开关不影响工具 |

### 与现有 presetId 机制的关系

- **presetId** = 能力面（Agent 预设：工具/模式/组合），首条消息定死（已有）
- **personaId** = 人格面（角色：SOUL.md + USER.md），同样首条消息定死
- 两者**正交**：换角色不换预设（工具能力不变），只换 `{{soul}}`/`{{user}}` 指向的文件

## 四、长期记忆机制（沿用 Cherry Studio 设计）

| 文件 | 用途 | 更新方式 | 上下文加载 |
|------|------|---------|-----------|
| `memory/FACT.md` | 长期事实（6 个月+） | memory 工具 `update`（原子覆盖）**或通用 fs 工具直接读写** | **首条消息快照**，内联加载 `<facts>` |
| `memory/JOURNAL.jsonl` | 一次性事件/会话笔记 | **只能通过 memory 工具** `append`/`search`（禁止通用 fs 直接写） | **不进上下文**，`search` 按需查 |

### memory 工具（仿 Cherry memoryTools.ts）

- `action=update`：`content` 整文件覆盖 FACT.md（实时写文件，但**当前会话的 `<facts>` 快照不变**，下个新会话才读新内容）
- `action=append`：`text` + `tags[]` 追加一行 `{ts, tags, text}` 到 JOURNAL.jsonl（实时写，搜索可查新条目）
- `action=search`：`query`（大小写不敏感子串）+ `tag` 过滤 + `limit`（默认 20），实时搜索文件

### 文件访问规则

- **FACT.md**：允许通用 fs 工具（read/write/edit）直接读写。记忆开关 OFF 时 AI 不知道有
  memory 目录（不注入契约），但用户告知后仍可用 fs 工具操作——无需强制 deny
- **JOURNAL.jsonl**：**只能通过 memory 工具 append/search 操作**，禁止用通用 fs 工具直接写
  （结构化日志，保证行格式一致）。**双保险**：
  1. 提示词契约 + 工具描述明确告知 AI（软约束）
  2. dsh-fork fs-local `deny` 清单加 `.agents/memory/JOURNAL.jsonl` 禁写（硬约束，低成本）
  - memory 工具 `search` 实现容忍坏行（skip 而非 throw），防历史坏数据
- **memory/snapshots/**：内部快照缓存，契约告知禁止读取/修改

### 首条消息快照规则

- **角色文件**（SOUL.md/USER.md）：首条消息时读文件 → 快照存快照文件 → 后续所有组装用快照
- **事实文件**（FACT.md）：首条消息时读文件 → 快照存快照文件 → 后续所有组装用快照
- **memory 工具写文件**：实时写入但不影响当前会话快照；AI 通过工具调用结果知晓写入内容，下个新会话的 `<facts>` 快照包含新内容
- **冷 resume**：进程重启后，从快照文件恢复；无快照（极旧会话）退化为首条消息时重新读文件
- **空内容不注入**：SOUL.md/USER.md/FACT.md 为空或纯注释（无实质文字）时，对应标签段落整个跳过（不污染 system prompt）

### 快照持久化

**配置（personaId/开关）与快照内容分离**：

| 数据 | 持久化位置 | 写入时机 |
|------|-----------|---------|
| `personaId` / `personaEnabled` / `memoryEnabled` | **App 每次请求携带** + DSH 侧 `sessionId → Map` 常驻内存 | 首条消息 `session/prompt` 参数传入，DSH 写入内存 Map |
| 快照内容 `{soul, user, facts}` | **快照文件** `.agents/memory/snapshots/<sessionId>.json` | 首条消息组装时（独立 section provider 首次被调用） |

> 为何不存 session meta：DSH 的 SessionHeader 是**固定白名单结构**（仅含
> `version/id/cwd/agentPreset/createdAt` 等有限字段），`personaId`/`personaEnabled`/
> `memoryEnabled` 传进去会被**静默丢弃**。`agentPreset` 能存只是因为它恰好
> 在白名单里。故**配置由 App 每次请求携带**，DSH 侧信任首条消息值并写入
> `sessionId → {personaId, personaEnabled, memoryEnabled}` 内存 Map（常驻，
> 仿现有 `this.selections` 模式）。

**快照文件格式**（`.agents/memory/snapshots/<sessionId>.json`）：

```json
{
  "persona": {
    "id": "meow-teacher",
    "soul": "SOUL.md 内容",
    "user": "USER.md 内容"
  },
  "memory": {
    "facts": "FACT.md 内容"
  },
  "createdAt": "2026-09-02T12:00:00.000Z"
}
```

**读取逻辑**（独立 section provider 每次组装时统一走 `ensureSnapshot(sessionId)` 单点）：
1. 查内存 Map `sessionId → snapshot` → 有则返回
2. 无 → 读快照文件 → 有则载入内存 Map 后返回
3. 无（首条消息/极旧会话）→ 读当前角色文件与 FACT.md → **一次写齐快照文件**（含 personaId/开关）→ 内存 Map → 返回
4. 之后文件改动不影响已固化快照
5. 快照写失败 → 仅保持内存 Map、不抛错、下次组装重试（provider 抛异常会炸掉整个 turn，必须兜底）

> **快照唯一所有者 = 独立 section provider**（每次 assemble 必被遍历）。
> 三个内容（soul/user/facts）统一走 `ensureSnapshot` 单点，避免"各自检查-创建"
> 产生半份快照。`{{soul}}`/`{{user}}` 变量仅作向后兼容透传，不再负责创建快照。

**快照文件清理**：会话删除时顺带删除对应 `<sessionId>.json` + jsonl 日志
（App 删 Room 行时同步清理快照文件，或新增 `session/delete` RPC 一次性清理）

### 提示词存储契约

仅在记忆开关 ON 时注入（首条消息快照）：
- 说明 memory/ 两文件的用途与更新方式（表格）
- 自主性授权："记忆自主更新，不用问用户"
- FACT.md 可用 memory 工具 update，也可直接读写
- JOURNAL.jsonl **只能经 memory 工具** append/search，禁止直接读写（fs deny 双保险）
- `memory/snapshots/` 是**内部快照缓存，禁止读取/修改**
- 写 FACT.md 前自问"6 个月后还有用吗？没用就 append"
- ⚠️ 当前会话的 `<facts>` 在首条消息时已固化快照，工具写入的新内容**下个会话才出现在注入中**

## 五、改动范围（预估）

### DSH 侧（随闭包进 runtime.bin，meow-jsonrpc.js + cordis.yml）

1. **`{{soul}}`/`{{soul_path}}` 变量按会话 personaId 解析，首条消息快照**
   - 从固定读取 `.agents/memory/SOUL.md` 改为从快照文件 `.agents/memory/snapshots/<sessionId>.json`
     读取（首条消息组装时由独立 section provider 生成快照）
   - 已查证：`systemPrompt.variable()` provider 能拿到 `context.agent.id`（= sessionId），
     从 `sessionId → {personaId,...}` 常驻 Map 取配置
   - **关键变化**：不再是每次组装实时读文件，而是**首条消息时读文件内容→快照存到
     `.agents/memory/snapshots/<sessionId>.json`**，后续组装从快照取（保证 KV 缓存前缀稳定）
   - personaId 缺失/角色文件夹被删 → 回退空灵魂（纯净模式）
   - 冷 resume：从快照文件恢复；无快照则退化为重新读文件

2. **新增 `{{user}}`/`{{user_path}}` 变量**
   - 同 `{{soul}}` 机制，首条消息快照 + 后续从快照取
   - 角色开关 OFF 时两个变量都返回空字符串

3. **新增 `memory` 工具（update/append/search）**
   - 在 `meow-jsonrpc.js` 的 `makeSetup(agentCtx)` 里按 `memoryEnabled` 决定是否注册到
     agent 作用域（`agentCtx.tools.register(defineTool({...}))`，仿 `tool-ask-user` 写法）
   - 读/写 `.agents/memory/FACT.md` + `.agents/memory/JOURNAL.jsonl`
   - 原子写入（临时文件 + rename）、O_APPEND 追加、子串搜索 + tag 过滤
   - **memory 工具实时操作文件，但不影响当前会话的 `<facts>` 快照**——AI 通过工具调用
     结果知晓写入内容，新内容仅在下个新会话的 `<facts>` 快照中体现

4. **memory 工具按开关条件注册**
   - `makeSetup(agentCtx)` 时检查 `memoryEnabled`（从 `sessionId → Map` 取）：
     - ON → `agentCtx.tools.register(defineTool({name:'memory',...}))`（per-agent scope）
     - OFF → 不注册，AI 完全不知道有 memory 工具
   - 角色开关**不控制任何工具**（SOUL/USER 走通用 fs 工具，始终可用）
   - 冷 resume 时 `makeSetup` 同样被调用，自动恢复工具注册状态

5. **`session/prompt` 参数携带 `personaId` + `personaEnabled` + `memoryEnabled`**
   - **App 每次请求（含 resume）都携带三字段**（Room 行为唯一事实源）
   - DSH 侧首条消息时信任参数值 → 写入 `sessionId → {personaId, personaEnabled, memoryEnabled}` 常驻 Map
   - 后续请求若与首条值不符 → warn 忽略（首条消息定死）
   - 冷 resume 时 App 仍携带（Room 行持久），Map 重建，快照从文件恢复

6. **新增 `personas/list` RPC**
   - 仿 `presets/list` 自动扫描 `.agents/personas/`，读每个角色的 `persona.yml`
   - 按 `.personas-order` 文件排序（未列出的角色按字母序排最后）
   - 返回：
     ```json
     { "personas": [
       {"id":"meow-teacher","name":"喵喵老师","description":"…","isDefault":true},
       {"id":"neko-companion","name":"小喵","description":"…","isDefault":false}
     ]}
     ```

7. **新增 `personas/reorder` RPC**（前端拖拽排序持久化）
   - 参数：`{order: ["meow-teacher", "neko-companion", ...]}`
   - 写入 `.agents/personas/.personas-order`（JSON 数组）
   - 幂等：缺失/重复 id 容错

8. **注入逻辑：按开关决定 section 内容，首条消息固化**
   - 角色 ON：注入 `<soul>` + `<user>`（首条消息快照）
   - 角色 OFF：跳过人格 section
   - 记忆 ON：注入 `<facts>` + 存储契约（记忆规则说明）（首条消息快照）
   - 记忆 OFF：跳过事实 section
   - 采用 Cherry Studio 的**独立追加 section** 模式（不依赖基座 persona 内的变量引用，
     直接注册 systemPrompt section，避免被自建预设的 `dsh-persona` 遮蔽）
   - **快照固化**：首条消息组装时，将 soul/user/facts 内容写入快照文件，后续组装直接
     复用，保证 system prompt 前缀不变（KV 缓存命中）

9. **基座 persona 调整**
   - 移除 `{{soul}}` 引用（人格注入改由独立 section 负责）
   - 保留环境说明四段（工作区/外观/安全边界）
   - **删除"记忆功能由后续版本提供…写入 workspace/memory/ 备用"占位行**（记忆说明改由记忆层 section 全权负责）
   - 记忆契约文案由独立 section 提供
   - 移除 plan-soul.md 中"改动从下一条消息起生效"的旧文案（改为首条消息快照锁定语义）
   - **变量 vs section 不重复注入**：基座与官方预设均不再引用 `{{soul}}`/`{{user}}`；
     保留变量注册仅为兼容旧自建预设（若其 persona 引用了变量，section 与变量取同一快照内容，
     自建预设作者自行避免重复引用）

### 安卓侧

1. `RuntimeExtractor`：
   - `.agents/personas/` 目录 + `README.md` + `skills/soul-md-generator/` 播种（assets 缺则播种、永不覆盖）
   - 默认角色 `default/` 播种：**单独判断**（不是 README 存在就跳过整树），
     `default/persona.yml` 不存在才复制，确保升级后新增的默认角色补种
   - 存量 `.agents/memory/SOUL.md` 迁移：
     - 复制内容到 `.agents/personas/default/SOUL.md`（若 `default/` 刚播种、SOUL.md 为空白模板则覆盖）
     - 旧文件改名 `.agents/memory/SOUL.md.bak`（或删除）
     - 告知用户：人格定义已迁至 `personas/default/`，旧文件已备份
2. Room Session 表新增字段（数据库迁移 **version 3 → 4**）：
   - `personaId: String?`（角色 id，可选，角色开关 OFF 时不绑定）
   - `personaEnabled: Boolean`（角色开关，默认 true）
   - `memoryEnabled: Boolean`（记忆开关，默认 true）
   - ⚠️ 存量会话升级后：`personaId=null` → 按回退链走默认角色；
     `personaEnabled/memoryEnabled=true`；升级即视为"首条消息已过"，按 Room 当前值固化
3. 聊天页（右侧看板「工作设置」页第三栏「角色设定」）：
   - 两个开关 toggle：[角色开关] [记忆开关]（各占一行）
   - [打开角色选择器] 按钮：
     - 角色开关 ON → 可用，点击弹出角色选择器窗口
     - 角色开关 OFF → **按钮灰掉不可点**（不弹窗，personaId 不绑定）
   - 角色选择器窗口：
     - 角色列表（名字 + 简介，来自 `personas/list`）
     - **新建角色 = 直接创建空白模板**（persona.yml + SOUL.md + USER.md 三件套空模板，
       不经过技能对话；后续可让 AI 用 soul-md-generator 技能填充，或手动编辑）
     - **长按拖拽排序 + 持久化**（`.personas-order` 文件，仿模型管理交互）
   - 会话页：会话标题上方 + 会话页内显示当前角色名（personaId 锁定的会话显示其角色，只读）
   - 角色名解析失败（角色文件夹被删）：显示「（已删除角色）」或 id 本身
4. 会话创建/恢复时透传 `personaId` + `personaEnabled` + `memoryEnabled` 到 `session/prompt` 参数
5. 会话删除时同步清理快照文件：在 `deleteSession` / `deleteSessions` 中追加
   `File(filesDir, ".agents/memory/snapshots/${sessionId}.json").delete()`

## 六、已确认的技术约束

- ✅ `systemPrompt.variable()` provider 签名 `(context: AssembleContext) => string`，
  `context.agent.id` = SessionId（已查 DSH 源码 `agent/src/dispatch.ts:175`）
- ✅ `ctx.tools.register(defineTool({...}))` 可注册工具（已查 `tool-ask-user` 模板）
- ✅ 工具可注册在 per-agent scope（`makeSetup(agentCtx)` 在 agentCtx 上操作）
- ✅ 所有注入点都在现有 `meow-jsonrpc.js` 插件内完成，零新增 npm 包

## 七、已细化确认的设计项

以下设计项已在本轮细化中明确，不再属于待定：

### 快照持久化
- **配置**（personaId/开关）：App 每次请求携带，DSH 侧 `sessionId → Map` 常驻内存
- **快照内容**（soul/user/facts 文本）：`.agents/memory/snapshots/<sessionId>.json`（首条消息组装时由独立 section provider 写入）
- 原因：DSH 的 SessionHeader 是固定白名单，没有 personaId/开关字段，传进去会被静默丢弃
- 冷 resume 无快照：首条消息时重新读文件写快照（一次性迁移语义）
- 快照写失败：不抛错，下次组装重试

### 角色排序
- `.agents/personas/.personas-order`：JSON 数组 `["id1","id2"]`
- 前端拖拽排序后调用 `personas/reorder` RPC 写入
- `personas/list` 返回时按此排序，未列出的角色按字母序排最后

### 默认角色
- 默认角色 id = `default`、显示名「喵喵老师」（迁移存量 `.agents/memory/SOUL.md` → `.agents/personas/default/SOUL.md`）
- `personas/list` 的 `isDefault = (id === 'default')`
- 回退链统一为：**personaId 有效 → 用之；personaId 空/未知/角色文件夹被删（解析失败）→ 默认角色（存在则用）→ 均缺 → 空灵魂（不注入任何人格内容）**

### RPC 协议
- `personas/list` → `{"personas": [{"id","name","description","isDefault"},...]}`
- `personas/list` 扫描规则：
  - 仅「含 persona.yml 的子目录」算角色（`.personas-order`/`README.md` 是文件、`skills/` 无 persona.yml → 自动排除）
  - 单角色容错：persona.yml 缺失/坏 YAML 用 try/catch 跳过，不使整个 list 失败
  - 排序：按 `.personas-order` 顺序，未列出的角色按字母序排最后
- `personas/reorder` → `{"order": ["id1","id2",...]}`；写入时**过滤掉不存在的角色 id**（与 list 扫描结果对拍），避免残留脏 id

### 角色显示 UI
- 会话标题上方：**小字标签**显示角色名
- 会话页内：消息列表上方/顶栏显示角色名
- 切换器：新建会话（空消息前）可自由切换；已有消息的会话锁定，显示角色名但不可切（灰掉/只读）
- 角色名解析失败（角色文件夹被删，`personas/list` 不返回该 id）：显示「（已删除角色）」或 id 本身；注入内容仍按快照继续（快照价值所在）

### 快照目录 AI 可见性
- `.agents/memory/snapshots/` 是**内部快照缓存**，存储契约中明确"禁止读取/修改"
- 提示词契约 + README 提及该目录为内部数据
- JOURNAL.jsonl 加 fs deny 双保险（dsh-fork fs-local 已有 deny 能力，低成本强约束：禁通用 fs 写 JOURNAL，允许 memory 工具 append/search）

### 内存 Map 生命周期
- `sessionId → {personaId,开关}` 与 `sessionId → snapshot` 两个 Map 均随会话销毁清理：
  - 会话删除时清对应条目
  - 进程内随 `agent/disposed` 事件清理
- 快照文件随会话删除清理（见下）

### 会话删除清理（P6 落地）
- **App 删 Room 会话行时，同步删除 `.agents/memory/snapshots/<sessionId>.json`**（App 有 filesDir 访问能力，chat 删除路径已有 dao.deleteSession）
- jsonl 日志清理：App 删 Room 时顺带通过 DSH 终端/RPC 清理对应日志（或暂缓，接受 jsonl 累积；文档标注为已知限制）

## 八、实施落地记录（2026-09-02）

实现按 `plan/plan-memory-execution.md` 落地后，与本文档原设想有 6 处出入，均属"实现比设计更严/更简"，
在此固化为准（后续以代码为准）：

1. **JOURNAL.jsonl 的 fs deny 是"禁读写"而非"只禁写"**：fs-local `deny` 的能力是在
   `resolve()`/`lstat()`/`listDir()` 拦截、命中即 `FS_PERMISSION_DENIED`，本就不区分读写方向。
   而 memory 工具的 `search`/`append` 走 **`node:fs` 直连**（不经 fs 服务），完全不受该 deny 影响。
   结果 = 通用文件工具对 JOURNAL.jsonl 既读不到也写不进、memory 工具照常工作——比原设想的
   "只禁写"更严，也与存储契约"禁止用文件工具直接读写该文件"的措辞一致。

2. **⚠️ 真机验收时发现 `deny` 在安卓上自 0.2.6 起一直空转，已扩 fork 修复（dsh-fork 0007）**：
   fs-local 拿"按 `resolve()` 归一化（不解析符号链接）的规则串"去比"realpath 派生的 targetKey"，
   而安卓上部署根有两个挂载别名——规则写 `/data/user/0/<pkg>/files/...`、realpath 解析成
   `/data/data/<pkg>/files/...`，永不相等 → **一条规则都不命中**。实测受害范围包括
   `appconfig/dsh-credentials.yaml`、`datastore/`、`config-defaults/`、`dsh-presets/`
   （即凭证与系统预设此前都能被通用文件工具读走）。
   修复（`packages/fs/fs-local/src/index.ts`）：① `denyRuleKeys()` 让每条规则同时保留词法形态与
   realpath 形态（路径尚不存在时沿最近存在祖先求 realpath 再补回后缀，与 `resolveLocalTarget` 同构）；
   ② `assertNotDenied()` 把 `targetKey` 与 `displayPath` 两面都比。
   并补 `describe('deny rules')` 4 例回归测试（此前 fork 对 deny **零测试覆盖**，才让它带了三个版本）。
   PC 冒烟测不出：只有"部署根可经多个挂载别名到达"时才暴露，必须真机验。

3. **角色层与记忆层共用一个 section**：注册单个 `meow:memory`（`order: 50`）而非两个。
   provider 内按 `personaEnabled`/`memoryEnabled` 决定拼哪几段，两开关均 OFF 时返回空串
   （`renderPrompt` 会丢弃空 section）。少一个注册点、少一处 name 冲突面。

4. **常驻 Map 提到模块级**（进程内跨 socket 连接共享），而非挂在 server 实例上：
   section/变量 provider 是**全局注册**的（`apply()` 里），拿不到 per-connection 的 server 实例。
   这也更贴合"常驻内存"的原文语义——App 重连不再丢配置。
   只有 `pendingMemoryConfigs` 暂存留在 server 实例（仿 `pendingHints`，随连接生命周期）。
   清理：`apply()` 里一个全局 `agent/disposed` 监听回收两个模块级 Map 条目。

5. **fresh install 不再播种 `.agents/memory/SOUL.md`**：`assets/agents/memory/SOUL.md` 已退役删除。
   新装用户的人格来自 `personas/default/`（空白模板，由 AI/用户填写，§三 决策表）；
   存量用户的老文件仍被 `migrateLegacySoul` 搬进 `personas/default/SOUL.md` 并改名 `.bak`。
   （若继续播种旧位置，会把"assets 的示例人格"误当成"用户存量人格"迁进 default，
   使默认角色永远拿不到空白模板语义。）

6. **额外提供"空白会话即时同步"**：设计只写了"新建会话时可自由切换"。实现上，改开关/换角色时
   除写 DataStore（新会话默认）外，若**当前会话零消息**还会回写它的 Room 行
   （`ChatDao.updateSessionPersona`）——否则用户点了"新会话"再改设置就会出现"改了不生效"。
   一旦有消息即锁定，不再写行（DSH 侧同样首条定死 + 不符只 warn）。

7. **persona.yml 缺失 vs 坏 YAML 区别对待**：目录**无** `persona.yml` → 不算角色（`skills/`、
   残留空目录自动落榜，§七 排除规则）；`persona.yml` **存在但解析不出字段** → 保留条目、
   name 回退 id、description 置空（用户仍能在选择器里看到它并修好/删掉，不会"角色凭空消失"）。

8. **「空 / 纯注释」判定把 markdown `#` 标题行也算作注释**（与 JS / Kotlin 两份实现同规则）：
   空白模板本身用 HTML 注释，正常写法不受影响；但一份**只有 `#` 标题、没有正文**的 SOUL.md
   会被判为无实质内容而整段跳过注入。可接受，记为已知限制。

9. **契约与角色指引一律给绝对路径**（真机验收纠偏）：`<facts>`/角色文件都在 filesDir 下，
   而会话 cwd 是 `files/workspace`，原文案的 `memory/FACT.md`、`.agents/personas/<id>/` 这类相对写法
   会让模型按 cwd 拼路径 → 读不到 → 转而用 read/bash 满盘找文件，绕开了 memory 工具。
   现注入文案写成 `${DSH_FILES_DIR}/.agents/...` 绝对路径，并在工具描述里点明「本工具无路径参数」。

10. **配置暂存的读取时机**：`getOrCreateSession` 返回未 await 的 creation promise，其 `finally`
   在函数 return 的当下就清暂存，而 `makeSetup` 在其后的异步体里才跑。因此 `personaId`/两开关
   必须**在函数开头同步捕获成局部变量、由闭包带进 makeSetup**（`pendingHints` 本来就是这么活的），
   不能在 makeSetup 里回查 Map——真机踩过：那样每个会话都静默落回「默认角色 + 双 ON」。

11. **开关类验收必须 ON/OFF 两侧都断言**：只测 ON 时，配置全链路丢失也表现为「一切正常」
    （默认值恰好就是 ON + default）。OFF 侧断言（记忆关闭后会话调不动 memory 工具）才是
    唯一能暴露配置没到的信号。

### 落地校验

- DSH 侧纯函数与 server 方法：`.tmp/memory-unit-harness.mjs`（桩掉 @deepseek-ai/* import 后直接
  跑仓库里那份 meow-jsonrpc.js）——81 项断言全过，覆盖开关四组合注入、快照三级读取与冷 resume
  恢复、personas 扫描排序容错、memory 工具三 action（含坏行容忍）、配置首条定死与 warn 忽略。
- 安卓侧迁移逻辑：`app/src/test/.../runtime/SoulMigrationTest.kt` 9 项 JVM 单测（新装/迁入/
  目标已占/纯模板/未播种/幂等/备份覆盖）。
- Room v4 schema 导出核对：`sessions` 列含 `personaId`/`personaEnabled`/`memoryEnabled`，
  与 `MIGRATION_3_4` 的 ALTER 一致。
- deny 的端到端断言方式：真机上模型会**自己按契约**拒用文件工具读 JOURNAL（探针里 `read`/`write`
  直接不发 tool call），因此硬约束的生效证据改测路径匹配——
  `realpath(filesDir/.agents/memory/JOURNAL.jsonl)` 与 cordis.yml 里 deny 规则串逐字符相等
  （确认 `/data/user/0` 未被符号链接重写成 `/data/data`，规则能命中 fs-local 的 targetKey）。