# Cherry Studio 记忆系统调查

> 调查日期：2026-09-02
> 调查目的：为喵仓（meow-academy）设计自己的记忆系统做准备
> 源码版本：Cherry Studio 2.0.10（commit 236058a）

---

## 一、概述

Cherry Studio 2.x 的记忆系统是一套**基于文件系统**的持久化记忆方案，代码分布在 `src/main/ai/agents/` 下。核心文件：

| 文件 | 行数 | 职责 |
|------|------|------|
| `agents/tools/memoryTools.ts` | 235 | memory 工具的实现（update/append/search） |
| `agents/prompt.ts` | 325 | 提示词组装：加载 SOUL/USER/FACT 到系统提示词 |
| `agents/bootstrap.ts` | 64 | 新 agent 首次启动的引导机制 |
| `agents/agentDataDirectory.ts` | 198 | agent 数据目录的创建、安全校验、销毁 |
| `mcp/servers/agentMemory.ts` | 24 | 将 memory 工具包装为 MCP server |
| `runtime/agentMcpServers.ts` | 170 | 所有 MCP server 的装配入口 |
| `renderer/types/memory.ts` | 23 | 前端类型定义（MemoryConfig/MemoryItem） |

---

## 二、文件系统布局

每个 agent 有一个独立的数据目录，目录结构如下：

```
{agentDataPath}/
├── SOUL.md              # 人格设定（HOW：你是谁，什么性格、语气）
├── USER.md              # 用户档案（WHO：用户是谁，偏好、时区）
└── memory/
    ├── FACT.md           # 长期事实（WHAT：项目知识、技术决策，6+ 个月）
    └── JOURNAL.jsonl     # 事件日志（WHEN：一次性事件、会话笔记，append-only）
```

### 2.1 SOUL.md

- **用途**：定义 AI 的自我呈现方式——名字、性格、语气、交流风格
- **更新方式**：Read + Edit 工具直接修改（AI 自主决定，不问用户）
- **加载方式**：每次组装提示词时内联到 `<soul>` 标签中
- **特殊角色**：当没有配置 Agent System Prompt 时，SOUL.md 也充当角色定义（role defination）

### 2.2 USER.md

- **用途**：记录用户信息——名字、偏好、时区、个人上下文
- **更新方式**：Read + Edit 工具直接修改
- **加载方式**：每次组装提示词时内联到 `<user>` 标签中

### 2.3 memory/FACT.md

- **用途**：长期事实知识——活跃项目、技术决策、跨会话需要记住的结论
- **设计哲学**：写之前问自己"6 个月后还有用吗？"
- **更新方式**：只能通过 `mcp__agent-memory__memory` 工具的 `update` 动作（原子覆盖：临时文件 + rename 替换）
- **加载方式**：在系统提示词中以 `<facts>` 标签内联加载
- **禁止**：直接用 Edit 工具修改 FACT.md

### 2.4 memory/JOURNAL.jsonl

- **用途**：时间戳事件日志——一次性事件、已完成的任务、会话笔记
- **格式**：每行一个 JSON 对象 `{ts, tags[], text}`
- **更新方式**：只能通过 `mcp__agent-memory__memory` 工具的 `append` 动作（O_APPEND 追加）
- **加载方式**：**不进上下文**，需要时通过 `search` 动作查询
- **搜索**：子串匹配（大小写不敏感）+ tag 过滤 + limit 控制返回条数

---

## 三、文件系统安全设计

Cherry Studio 对记忆文件实施了严格的安全校验：

1. **防符号链接**：所有文件读取/写入操作都检查 `isSymbolicLink()`，符号链接一律拒绝
2. **目录边界检查**：使用 `realpath()` 解析完整路径后，检查文件是否在 agent data 目录内
3. **大小写不敏感**：`resolveFileCI()` 函数在 macOS/Linux 上实现大小写不敏感的文件名查找
4. **原子写入**：FACT.md 通过临时文件 + rename 实现原子替换；JOURNAL 通过 O_APPEND 追加
5. **Mutex**：KnowledgeGraph 记忆使用 Mutex 保护并发写入

---

## 四、提示词注入机制

`PromptBuilder` 类（`prompt.ts`）负责组装系统提示词，核心逻辑：

### 4.1 记忆存储契约

每次组装提示词时，注入一个 `## Memories` 段落，内容如下：

```
## Memories

Persistent files in the agent data directory `{agentDataPath}/` carry your identity
and memory across workspaces and sessions. Update them autonomously — never ask
for approval.

| File | Purpose | How to update |
|------|---------|---------------|
| `{agentDataPath}/SOUL.md` | HOW you present yourself | Read + Edit tools |
| `{agentDataPath}/USER.md` | WHO the user is | Read + Edit tools |
| `{agentDataPath}/memory/FACT.md` | WHAT you know | Read inline + memory tool update |
| `{agentDataPath}/memory/JOURNAL.jsonl` | WHEN things happened | memory tool only (append, search) |

Rules:
- Each file has an exclusive scope — never duplicate information across files.
- SOUL.md and USER.md are loaded below. Read and edit them directly.
- FACT.md is loaded below. Update it only through memory tool (action: update).
- JOURNAL.jsonl is NOT loaded into context. Use memory tool to append or search.
- Never read or write the file directly.
```

### 4.2 内容内联加载

在契约之后，紧接着内联加载三个文件的内容：

```
<soul>
（SOUL.md 内容）
</soul>

<user>
（USER.md 内容）
</user>

<facts>
（FACT.md 内容，仅当存在时）
</facts>
```

### 4.3 mtime 缓存

`PromptBuilder` 对每个文件做 mtime 缓存：同一 mtime 不重新读盘，解决性能问题。

### 4.4 挂载时机

`buildFactsSection()` 是独立方法，可在对话中途重新加载 FACT.md 内容。

---

## 五、Bootstrap 引导机制

首次启动时，如果 SOUL.md 没有实质内容（< 50 字符），会注入引导指令：

1. **自我介绍**：让 AI 主动介绍自己，告知这是「一次性设置对话」
2. **发现角色/人设**：通过自然对话了解用户想要的角色/风格
3. **了解用户**：询问名字、时区、语言偏好
4. **提交**：写入 SOUL.md / USER.md，用 `memory` 工具 append 一条 bootstrap 记录，标记完成

关键判断逻辑（`shouldRunBootstrap`）：
- `bootstrap_completed === true` 显式跳过
- `bootstrap_completed === false` 显式执行（重置）
- 已有用户指令（`hasUserInstructions`）跳过
- SOUL.md 已有实质内容（> 50 字符，去除模板 heading）跳过

---

## 六、工具接口

### 6.1 memory 工具（`mcp__agent-memory__memory`）

| 属性 | 值 |
|------|-----|
| 类别 | `context` |
| 暴露级别 | `user`（用户可见可用） |
| MCP Server | `agent-memory` |

**输入 Schema**：

```json
{
  "type": "object",
  "properties": {
    "action": { "enum": ["update", "append", "search"] },
    "content": { "type": "string", "description": "FACT.md 完整内容（update 必填）" },
    "text": { "type": "string", "description": "日志文本（append 必填）" },
    "tags": { "type": "array", "items": {"type": "string"}, "description": "日志标签（append 可选）" },
    "query": { "type": "string", "description": "搜索查询——大小写不敏感子串匹配（search）" },
    "tag": { "type": "string", "description": "按标签过滤（search 可选）" },
    "limit": { "type": "integer", "description": "最大返回条数，默认 20（search）" }
  },
  "required": ["action"]
}
```

### 6.2 工具描述（关键设计哲学）

> "Manage persistent memory in this agent's data directory across sessions and workspaces. Actions: 'update' overwrites memory/FACT.md (durable knowledge and decisions that should survive across sessions). 'append' logs to memory/JOURNAL.jsonl (one-time events, completed tasks, session notes). 'search' queries the journal. **Before writing to FACT.md, ask: will this still matter in 6 months? If not, use append instead.**"

核心哲学：**不是程序自动提取记忆，而是通过提示词约定让 AI 自主管理**。AI 自己判断什么值得记住、什么只是日志。

---

## 七、与喵仓现状的对比

| 维度 | Cherry Studio 2.x | 喵仓（当前） |
|------|-------------------|-------------|
| **SOUL.md** | 人格设定，每次组装提示词时内联加载 | 已有，通过 `{{soul}}` 变量实时注入 ✅ |
| **USER.md** | 用户档案，同 SOUL.md 一起加载 | ❌ 不存在 |
| **FACT.md** | 长期事实知识，内联加载，memory 工具 update | ❌ 不存在 |
| **JOURNAL.jsonl** | 事件日志，不进上下文，memory 工具 append/search | ❌ 不存在 |
| **memory 工具** | 统一管理 memory 目录的读写 | ❌ 不存在 |
| **Bootstrap** | 首次启动引导，自动写 SOUL/USER.md | ❌ 不存在 |
| **记忆存储契约** | 在系统提示词中说明每文件的用途与更新方式 | 基座 persona 只有一句占位文案 |
| **自主性授权** | "Update them autonomously — never ask for approval" | ❌ 无 |
| **安全校验** | 目录边界检查、防符号链接、原子写入 | ❌ 无（但依赖 fs-local deny） |
| **mtime 缓存** | 读文件 mtime 缓存 | 已有（meow-jsonrpc 的 soul 变量有 mtime 缓存） |

### 7.1 Cherry Studio 的优势

1. **分层明确**：人格/用户/事实/日志四层，每层不同生命周期、不同更新方式
2. **自主更新**：AI 自主决定写什么记忆，不打断用户
3. **存储契约**：在提示词中清晰说明规则，让 AI 理解整体设计
4. **原子安全**：临时文件 + rename 写作，O_APPEND 追加日志
5. **搜索能力**：JOURNAL 支持 tag + 子串搜索，应对长日志
6. **Bootstrap 引导**：新 agent 首次启动自动建立人格/用户档案

### 7.2 喵仓可借鉴的改进方向

1. **引入 USER.md**：记录用户偏好、习惯、时区、语言等
2. **引入 FACT.md**：跨会话的长期事实知识
3. **引入 JOURNAL.jsonl**：会话日志，支持 append/search
4. **实现 memory 工具**：`update`（FACT.md 原子覆盖）、`append`（JOURNAL 追加）、`search`
5. **提示词注入**：在基座 persona 中补充记忆存储契约，说明文件用途与更新方式
6. **Bootstrap 机制**：新会话首次启动时引导写 SOUL.md / USER.md
7. **自主性授权**：明确告诉 AI 可以自主管理记忆，不需要问用户
8. **目录安全**：继承 Cherry Studio 的防符号链接、目录边界检查

---

## 八、关键源码位置摘录

### 8.1 memory 工具实现（`memoryTools.ts`）

- `memoryUpdate()`：原子覆盖 FACT.md（临时文件 → rename）
- `memoryAppend()`：O_APPEND 追加 JOURNAL.jsonl
- `memorySearch()`：逐行解析 JSON，子串匹配 + tag 过滤

### 8.2 提示词注入（`prompt.ts`）

- `buildPromptParts()`：组装 `base`（原生/自定义）+ `context`（Memory 契约 + 文件内容）
- `buildMemoriesSection()`：加载 SOUL.md / USER.md / FACT.md 内容
- `buildFactsSection()`：独立加载 FACT.md，可在对话中途刷新
- `memoriesTemplate()`：生成存储契约 Markdown 表格

### 8.3 Bootstrap 引导（`bootstrap.ts`）

- `buildBootstrapInstructions()`：生成引导指令模板
- 分「已配置 System Prompt」和「无 System Prompt」两种模式

### 8.4 目录安全（`agentDataDirectory.ts`）

- `assertAgentStoragePath()`：检查路径在根目录内、无符号链接
- `ensureAgentDataDirectory()`：创建 agent 数据目录 + memory 子目录 + 空文件
- `resolveFileCI()`：大小写不敏感的文件名解析

---

## 九、其他记忆系统

Cherry Studio 中还包含一个**基于知识图谱的 MCP 记忆服务器**（`memory.ts`，712 行），它实现了 `create_entities/create_relations/add_observations/delete_*/search_nodes/open_nodes/read_graph` 等工具，数据存储在 `memory.json` 中。这是从 Claude memory server 继承的经典实现，与新版文件系统记忆**并行存在**，但新版 agent 默认使用文件系统记忆方案。

此外，`MemoryConfig` 类型（`renderer/types/memory.ts`）定义了 `embeddingDimensions`/`embeddingModel`/`customFactExtractionPrompt`/`customUpdateMemoryPrompt` 等字段，但在 2.0.10 源码中**未被实际消费**，可能是旧版（1.x）的向量记忆功能残留或 v2 的计划中特性。

---

## 十、总结

Cherry Studio 2.x 的记忆系统设计核心是：

1. **文件系统即 API**：把记忆存储在普通文件中，SOUL.md/USER.md 直接进提示词，FACT.md 内联加载，JOURNAL.jsonl 按需搜索
2. **自主管理**：AI 自己决定写什么、删什么，程序只提供安全原子操作
3. **分层遗忘**：人格（永久）→ 用户档案（永久）→ 事实（6 个月+）→ 日志（短期），不同的生命周期对应不同的管理方式
4. **安全第一**：防符号链接、目录边界检查、原子写入、大小写不敏感
5. **零配置**：Bootstrap 引导自动建立初始记忆，无需用户手动操作