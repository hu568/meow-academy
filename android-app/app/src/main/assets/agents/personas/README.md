# 角色库（personas）

本目录存放喵仓的所有角色设定。每个角色占一个子文件夹，包含三个文件：

```
personas/
├── README.md                                         # 本文件——角色库说明
├── .personas-order                                   # 角色排序（前端拖拽持久化，JSON 数组）
├── skills/
│   └── soul-md-generator/                            # 内置角色创建技能
│       ├── SKILL.md                                  # 技能说明（含完整工作流程）
│       └── references/                               # 模板与示例
│           ├── templates.md                          # SOUL.md 结构变体
│           └── examples/
│               └── catgirl-companion-example.md      # 完整角色示例（三件套）
└── <角色id>/                                          # 一个角色一个子文件夹
    ├── persona.yml                                   # 角色元数据（名字+简介，前端展示用）
    ├── SOUL.md                                       # 角色人格设定（进系统提示词）
    └── USER.md                                       # 该角色专属的用户档案（进系统提示词）
```

## 如何创建角色？

阅读 `skills/soul-md-generator/SKILL.md` 按流程操作——先访谈收集角色信息，再生成三件套写入 `personas/<角色id>/`。

## 角色管理

- **前端切换**：在聊天页右侧看板 → 工作设置 → 角色设定，可开关角色/记忆注入、选择角色
- **自动发现**：`personas/list` RPC 自动扫描本目录，新建的角色立刻出现在角色选择器中
- **文件管理**：编辑角色文件（`SOUL.md`/`USER.md`）内容**只影响新会话**——所有记忆注入（人格/用户/事实）在会话首条消息时固化快照，之后保持不变（保证 KV 缓存前缀稳定）；已开始的会话不受文件改动影响

## 与长期记忆（`../memory/`）的关系

角色库与记忆目录**平级且正交**——换角色不换记忆：

| 位置 | 内容 | 作用域 | 谁能写 |
|------|------|--------|--------|
| `personas/<id>/` | `persona.yml` + `SOUL.md` + `USER.md` | 每会话绑定一个角色（首条消息定死） | 通用文件工具 |
| `memory/FACT.md` | 长期事实（跨会话，6 个月+） | 全局共享 | memory 工具 `update` 或通用文件工具 |
| `memory/JOURNAL.jsonl` | 一次性事件/会话笔记（append-only） | 全局共享 | **仅** memory 工具 `append`（通用文件工具被 fs deny 拒绝读写） |
| `memory/snapshots/` | 会话首条消息的注入快照缓存 | 每会话一份 `<sessionId>.json` | 系统内部，**禁止读取/修改** |

## 注意事项

- 角色 id = 文件夹名（小写+中横线风格，如 `meow-teacher`、`neko-companion`）
- 删除角色 = 删除整个子文件夹
- 角色设定与 Agent 预设（能力面）正交：换角色不影响工具能力
- 角色开关 OFF 的会话不注入任何人格内容；记忆开关 OFF 的会话不注入 `<facts>`、也没有 memory 工具
