# 版本更新记录

## [0.2.6] - 2026-08-30（debug）

第八个 debug 包（约 94MB）。核心变更：**补完「普通模式」——接入 DSH 原生 agent-presets 体系（Agent 预设）+ 能力工具 GUI 化（附加模式胶囊 / 悬浮栏 / 问答卡 / 工作设置页）**。计划与实施细节见 `plan/plan-standard-mode.md`。

**新特性：**

- 🧩 **Agent 预设体系**：接入 DSH 原生 `agent-presets`（`meow-standard` 全家桶预设，默认启用：喵喵老师 persona + 文件/终端/联网/待办/技能/目标/后台任务/规划模式/子代理/问答卡全套工具）；`presets/list` 动态扫描（用户在 `filesDir/.dsh/.agent-presets/` 自建的预设自动出现在列表，为创造预设铺路）；PTC/极简/创造三张占位卡灰显待后续落地；预设挂载失败/未知预设的 jsonrpc 错误原文透传进聊天气泡
- 📎 **聊天栏附加模式胶囊**：移除 provider/model 两个圆钮（切换全权归右侧看板「模型管理」），原位合并为附加模式胶囊——「规划」（plan-mode）与「目标」（goal）在此附加/关闭，空态/生效中/确认三态，状态确认以 `plan/mode`/`goal/change` 事件为准
- 📊 **上方悬浮栏**（新组件）：todo（`☑ n/m` + 展开三态清单）与 subagent（spawn 计数 + 收尾摘要）两态切换显示，无数据不占位
- ❓ **问答卡**：`ask_user_question` / `exit_plan_mode` 工具调用渲染为专用卡片——默认展开、primaryContainer 配色区分、可折叠；选项单选/多选 + 自由文本；计划审阅卡正文 Markdown 渲染（限高滚动）+ 中文按钮（批准/继续规划，回传原文 label）；回答后折叠为已答记录
- 🗂 **右侧看板「工作设置」页**（第 4 页签）：三栏布局——①工作区（新会话默认工作区切换/添加，目录选择器浏览 filesDir + 新建文件夹；当前工作区会话列表）②Agent 预设（卡片列表 + 可折叠说明 + 设默认/长按删除自定义）③记忆/角色（喵喵老师角色卡，记忆占位待后续）
- 💾 **会话按工作区隔离**：Room v3（`presetId` + `workspacePath` 归属缓冲，首条消息定死）；切工作区只写设置不重启 DSH、不打扰生成中会话；会话抽屉新增「全部会话 / 当前工作区会话」过滤 + 三行布局（标题 / 预设名·工作区名 / 紧凑时间）
- 🏷 **顶栏状态小字**：标题上方显示「工作区 · 预设名」；进入聊天页自动打开最近会话（删除当前会话后自动落位）；快捷文件工作区模式跟随当前会话的工作区

**DSH 侧（闭包 +12 包，runtime.bin 重打 75MB）：**

- 新增 `dsh-agent-presets` / `dsh-persona` / `dsh-plan-mode` / `dsh-commands` / `dsh-command-goal` / `dsh-user-questions` / `dsh-tool-ask-user` / `dsh-tool-subagent` / `dsh-tool-subagent-control` / `dsh-subagent-spawn-in-process` / `dsh-command-compact` / `dsh-subagent-in-process-driver`
- `meow-jsonrpc.js` 扩展：`presets/list`·`read`·`delete`、`session/command`（斜杠命令通道）、`session/query`（状态水合）、`session.question` 通知 + `session/answerQuestion`（问答通道，连接生命周期管理）、prompt/command 参数携带 `presetId`/`cwd`、resume 按日志重挂预设、MeowRpcError 结构化错误码（PRESET_UNKNOWN/-32001 等五类）
- fork 补丁新增 **0002**（str-replace-editor 接会话 cwd，跨工作区会话 str_replace 落点修正；含 deploy 清单 + lockfile），0001 不变，基线仍为 `dsh-v0.1.1-rc.2`
- 环境注入：`DSH_HOME=filesDir/.dsh`（用户预设根/skills 根迁移）、`DSH_CWD` 按设置的工作区返回；`dsh-presets/` 资产按 sync-token 播种

**验证状态**：PC 冒烟 10 项全绿（initialize / presets/list / session/command /plan·/goal / session/query / 错误映射 / 冷 resume 重挂 / 插件 active 清单）；真机验证 DSH 子进程存活、initialize / presets/list / session/query / settings/describe 并发请求全部有响应、连接稳定（首装曾因闭包丢 `@img/sharp-wasm32` 致 DSH 子进程启动即崩，已修复并固化 dsh-fork 0003，见 AGENTS.md 踩坑记录）；完整验收清单见 `plan/plan-standard-mode.md` §八。

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.5...v0.2.6
