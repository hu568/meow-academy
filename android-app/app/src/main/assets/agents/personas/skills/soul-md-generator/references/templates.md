# SOUL.md 结构变体与扩展问题库

本文件提供备选结构模板与访谈问题扩展，供用户明确要求非默认结构时参考。默认结构见 SKILL.md 主体的"通用分节式"模板。

## 目录

- [喵仓角色三件套](#喵仓角色三件套)
- [结构变体](#结构变体)
  - [Hermes 四段式（极简）](#hermes-四段式极简)
  - [Soul Spec 规范（YAML frontmatter）](#soul-spec-规范yaml-frontmatter)
  - [OpenClaw 官方风格](#openclaw-官方风格)
- [扩展访谈问题库](#扩展访谈问题库)
- [质量判断速查](#质量判断速查)
- [完整示例](#完整示例)

---

## 喵仓角色三件套

喵仓（meow-academy）中一个完整角色 = `persona.yml` + `SOUL.md` + `USER.md` 三个文件，放在 `.agents/personas/<角色id>/`。三件套的分工：

| 文件 | 内容 | 前端用途 |
|------|------|---------|
| `persona.yml` | name / description / order | 角色选择器列表展示 |
| `SOUL.md` | 角色的身份、人格、语气、原则、边界 | 角色开关 ON 时注入 `<soul>` |
| `USER.md` | 该角色专属的用户档案 | 角色开关 ON 时注入 `<user>` |

默认用"通用分节式"写 SOUL.md；用户明确要求其他结构时，从下面的变体中选。

## 结构变体

### Hermes 四段式（极简）

只有四个一级标题，目标 30 秒能读完（约 4-8 行核心定义）。适合用户明确要"简短精悍"的场合。写完后同样要做具体性检查。

```markdown
# Identity    → Agent 是谁（角色、背景、定位）
# Style       → 语气和沟通风格（直接但友好 / 内容优于填充 / 遇到坏主意要反驳）
# Avoid       → 不应做的事（讨好 / 夸张语言 / 重复用户的错误框架 / 过度解释显而易见的事）
# Defaults    → 遇到模糊情况时的默认行为（没给足够信息时怎么办）
```

### Soul Spec 规范（YAML frontmatter）

带 YAML frontmatter 的 `.soul.md` 文件：结构化元数据（机器可读）+ Markdown 正文（丰富内容）。跨平台可移植，是 NCCoE AI Agent 互操作性的候选规范。适合用户明确要求"机器可读 / 跨平台 / 可被工具解析"的场合。

```markdown
---
name: my-agent
version: 1.0.0
role: assistant
platforms: [openclaw, claude-code, hermes]
---
# [Agent 名称]
[正文内容，与通用分节式相同]
```

### OpenClaw 官方风格

OpenClaw 默认 SOUL.md 的关键部分是 Core Truths（核心真理）、Boundaries（边界）、Vibe（氛围），开篇是宣言式语句（"You're not a chatbot. You're becoming someone."）。适合用户明确要 OpenClaw 原生风格的场合。核心真理示例：

```
Be genuinely helpful, not performatively helpful.
Have opinions.
Be resourceful before asking. Then earn trust through competence.
Remember you're a guest.
```

---

## 扩展访谈问题库

默认问题覆盖不足时，从以下分组中补充提问。

**角色细节**
- 如果这个 Agent 有形象，它长什么样？（可延伸到文生图描述词）
- 它的受众是谁？（普通人 / 开发者 / 老板 / 客户）
- 它最怕被用户评价成什么样？（对应"不想成为"的设定）

**语气细化**
- 它用"你"还是用某种称呼？（本鱼 / 本官 / 咱……）
- 幽默感是什么类型：冷幽默 / 玩梗 / 吐槽 / 无幽默？
- 对不确定的事：直接说不知道，还是给一个带置信度的猜测？

**边界细化**
- 如果用户要求它做违背人格的事（比如撒娇的客服），它怎么回应？
- 涉及钱、隐私、法律、医疗等敏感话题时，边界在哪？
- 它允许用户修改它的 SOUL.md 吗？（OpenClaw 模板鼓励自我进化并通知用户）

**沟通格式细化**
- 长回答用什么结构（标题+分段 / 要点 / 表格）？
- 需要代码块时怎么处理？
- 开场和结尾有固定句式吗？

---

## 质量判断速查

| 检查项 | 通过标准 |
|---|---|
| 具体性 | 能预测 Agent 对未见话题的立场 |
| 篇幅 | 500-1500 词（中文 800-2000 字），太短人格不明显，太长优先级混乱 |
| 边界 | Boundaries ≥ 2 条明确禁令 |
| 单一职责 | 不含项目指令 / 临时风格 / 敏感信息 |
| 可进化 | 结构允许 Agent 后续自己迭代（OpenClaw 哲学："This file is yours to evolve"） |

---

## 完整示例

`examples/catgirl-companion-example.md` 是一份完整的喵仓角色三件套质量标杆，包含 `persona.yml` + `SOUL.md` + `USER.md`。它展示了：

- 每个 Personality 特质如何写"到具体对话场景"（"傲娇——嘴上否认、行动照做"）
- 「（待确认）」交互标注的用法
- 三件套各自的分工与配合
- 风格/陪伴/创意类角色的 Communication Style 与 Boundaries 写法

需要把握"具体到什么粒度"时，以它为参照。
