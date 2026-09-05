# 版本更新记录

## [0.2.9] - 2026-09-05（debug）

第十一个 debug 包（约 95MB）。核心变更：**记忆系统（角色库 + 长期记忆 + 首条快照锁定）落地与加强** + 文件分享/导出 + 空白会话预设自由切换 + deny 名单 realpath 安全修复。

**新特性：**

- 🧠 **记忆系统**：把「你是谁」与「你记得什么」拆成两套正交机制，各有会话级开关，全部在首条消息固化快照（保 system prompt 前缀恒定命中 DeepSeek KV 缓存）。
  - **角色库** `.agents/personas/<id>/`：一角色一目录三件套（`persona.yml` + `SOUL.md` + `USER.md`），内置 `default`「喵喵老师」+ `soul-md-generator` 创建技能；看板「工作设置→角色设定」两开关 + `PersonaPickerDialog`（选择/新建/删除/长按拖拽排序），顶栏小字与抽屉标题上方显示角色名
  - **长期记忆** `.agents/memory/`：`FACT.md` 全局共享、首条消息内联 `<facts>`；`JOURNAL.jsonl` append-only 不进上下文、按需 search；新增 `memory` 工具（update/append/search），按开关注册到 agent 作用域
  - **快照锁定**：首条消息落 `.agents/memory/snapshots/<sessionId>.json`，删会话顺带删快照；DSH 侧 `sessionId → 模块级常驻 Map` 首条定死、冷 resume 从快照重建
  - **升级**：Room v3→v4（sessions + `personaId`/`personaEnabled`/`memoryEnabled`）；存量 `.agents/memory/SOUL.md` 有实质内容自动迁入 `personas/default/SOUL.md` 并改名 `.bak`
  - **0.2.9 加强**：修复两个静默失效——① 契约/角色指引误用相对路径导致模型探路绕开工具，改绝对路径并点明「无路径参数」；② `getOrCreateSession` 的 `finally` 在 return 当下清暂存导致 `personaId`/开关被静默丢弃，改为同款同步捕获快照传入（教训：开关类验收必须 ON/OFF 两侧都断言）
- 📤 **文件分享/导出**：App 私有文件经 FileProvider 换 `content://` 临时授权分享；多选/目录支持 zip 打包导出；多选栏与长按菜单分享入口（路径范围只覆盖文件管理两个浏览根 + 分享临时 zip 目录）
- 🔀 **空白会话预设可自由切换**：首条消息前可在「工作设置→Agent 预设」栏切换空白会话的预设归属（Room 行同步）；删除预设回退兜底；首条后锁定（有消息的会话由 UI 层拦住不调用）
- 🧭 **聊天快捷文件面板面包屑可点跳转**：紧凑单行（32dp 图标钮）+ 自动滚动到当前目录 + 修复中间段闭包捕获导致点不动

**本包修复（DSH 侧）：**

- 🐛 **deny 名单 realpath 安全修复（dsh-fork 0007）**：`fs-local` 的 deny 规则此前只按 `resolve()` 词法形态比 `realpath` 派生的 targetKey，而安卓部署根有两个挂载别名（写 `/data/user/0/<pkg>/files/...`、realpath 出 `/data/data/<pkg>/files/...`）永不相等——自 0.2.6 起整份 deny 名单（凭证/datastore/config-defaults/dsh-presets）全部空转拦不住（真机让 AI 读走 JOURNAL.jsonl 才暴露）。修复：规则同时保留词法 + realpath 双形态、路径不存在沿最近存在祖先求 realpath 补后缀、`assertNotDenied` 两面都比；补 4 例回归（fork 此前对 deny 零测试覆盖）
- 🔧 **设置页终端描述 DSH 化**：终端入口副标题「pi RPC bash」→「DSH bash」；终端快捷命令固定暗色文字（深底浅字，浅色主题下可读）

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.8...v0.2.9