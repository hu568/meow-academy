# 版本更新记录

## [0.2.6] - 2026-08-30（debug）

第八个 debug 包（约 95MB）。核心变更：**补完「普通模式」与「创造模式」——DSH 原生 Agent 预设体系 + 能力工具 GUI 化 + Cordis 插件化创造（host-only 无审批）**，另有灵魂/身体分离（SOUL.md）、第三方模型思考强度声明、终端 node/bash 直跑、文件管理资源管理器化一批。计划见 `plan/plan-standard-mode.md` / `plan/plan-creative-mode.md` / `plan/plan-soul.md`。

**普通模式（DSH 原生 Agent 预设 + 能力工具 GUI）：**

- 🧩 **Agent 预设体系**：接入 DSH 原生 `agent-presets`（`meow-standard` 全家桶预设，默认启用：文件/终端/联网/待办/技能/目标/后台任务/规划模式/子代理/问答卡全套工具，人设经全局 SOUL.md 注入，见下）；`presets/list` 动态扫描（用户在 `filesDir/.dsh/.agent-presets/` 自建的预设自动出现在列表）；PTC/极简两张占位卡灰显待后续落地（创造已随本版播种为真实预设）；预设挂载失败/未知预设的 jsonrpc 错误原文透传进聊天气泡
- 📎 **聊天栏附加模式胶囊**：移除 provider/model 两个圆钮（切换全权归右侧看板「模型管理」），原位合并为附加模式胶囊——「规划」（plan-mode）与「目标」（goal）在此附加/关闭，空态/生效中/确认三态，状态确认以 `plan/mode`/`goal/change` 事件为准
- 📊 **上方悬浮栏**（新组件）：todo（`☑ n/m`）与 subagent（spawn 计数 + 收尾摘要）两态切换显示，无数据不占位；默认折叠一行摘要，点击展开半透明面板浮于消息列表上层（消息从面板下方透过，todo 展开为三态清单、subagent 展开为逐代理状态行），再点收起
- ❓ **问答卡**：`ask_user_question` / `exit_plan_mode` 工具调用渲染为专用卡片——默认展开、primaryContainer 配色区分、可折叠；选项单选/多选 + 自由文本；计划审阅卡正文 Markdown 渲染（限高滚动）+ 中文按钮（批准/继续规划，回传原文 label）；回答后折叠为已答记录
- 🗂 **右侧看板「工作设置」页**（第 4 页签）：三栏布局——①工作区（新会话默认工作区切换/添加，目录选择器浏览 filesDir + 新建文件夹；当前工作区会话列表）②Agent 预设（卡片列表 + 可折叠说明 + 设默认/长按删除自定义）③记忆/角色（喵喵老师角色卡，记忆占位待后续）
- 💾 **会话按工作区隔离**：Room v3（`presetId` + `workspacePath` 归属缓冲，首条消息定死）；切工作区只写设置不重启 DSH、不打扰生成中会话；会话抽屉新增「全部会话 / 当前工作区会话」过滤 + 三行布局（标题 / 预设名·工作区名 / 紧凑时间），抽屉悬浮化（收进系统栏留缝 + 右缘圆角，对齐右侧看板风格）
- 🏷 **顶栏状态小字**：标题上方显示「工作区 · 预设名」；进入聊天页自动打开最近会话（删除当前会话后自动落位）；快捷文件工作区模式跟随当前会话的工作区

**创造模式（Cordis 插件化创造，host-only 全程无审批）：**

- 🪄 **`meow-cordis` 预设播种**：= meow-standard 全量 + `tool-cordis` 自指工具集 + 两份中文教学技能（`editing-cordis-compositions` / `cordis-plugin-development`，按 baseline API + 喵仓部署魔改：路径/deny/无沙箱无审批/外观诉求路由 appconfig）；Kotlin 侧占位卡删除，播种后 `presets/list` realIds 过滤自动接管
- 🔌 **host-only 部署**：基座挂 host 平面 `cordis-host-runner` 行（动态插件注册表/生命周期/VM 执行），`cordis_run` 直接激活不经审批——Web 前端砍掉，创造全程无审批；fork patch **0004** 补 `dsh-tool-cordis` / `dsh-cordis-host-runner` 两包，`CORDIS_SYSTEM_PROMPT` 顶部 headless 部署适配段（只写 code.host、cordis_run 同步激活、外观诉求不写插件）
- 🔐 **预设访问纪律**：系统预设 `dsh-presets/` deny 读写都拒（AI 改预设走 roster `list`/`read`/`copy`/`standingKeyFor` 接口），用户预设根 `${DSH_HOME}/.agent-presets/` 直接可写——AI `copy-preset` 复制系统预设改造、自建预设闭环可用

**灵魂/身体分离（人设迁出预设）：**

- 💞 **SOUL.md 唯一定义处**：喵喵老师人物设定从预设迁入 `filesDir/.agents/memory/SOUL.md`（assets 缺则播种、永不覆盖，AI/用户可直接编辑，改完下一条消息生效）；`meow-jsonrpc` 注册全局 prompt 变量 `{{soul}}`/`{{soul_path}}`（每次组装提示词实时读文件，mtime 缓存；文件缺失/为空 = 不带人设的纯净助手兜底）
- 🧼 **预设回归纯净模式**：基座 persona = `{{soul}}` + 部署环境四段（工作区/外观路由/记忆/安全边界，全预设共享）；`meow-standard` 预设删 persona 行，只含工具与模式组合；用户自建预设如需覆盖人设可挂 `@deepseek-ai/dsh-persona` 行（scoped 遮蔽基座）

**第三方 provider 思考强度 + 缓存绝对值：**

- 🧠 **思考能力声明补齐**：第三方模型此前被 llm 核心按「无思考能力」拒掉一切显式 effort，根因是能力声明没下发（DSH 侧 pi-ai 的方言机制本就齐全）——模型条目新增 `reasoningEfforts` 配置（模型管理对话框：开关 + 档位 chips + wire 值编辑 + 四模板；off 恒含且 value=null =「支持该档但不发参」，与「未声明」严格区分）+ provider 级 `compat.thinkingFormat`（「思考参数格式」下拉，自动 = 交 pi-ai 按端点探测）
- ⚡ **聊天页思考档位动态渲染**：按 `llm/models` reasoning 元数据 + `setModel` 响应 modelReasoning 双来源刷新；DeepSeek 官方兜底 off/high/max，第三方无声明 = 闪电钮禁用置灰提示
- 📈 **调用量看板缓存绝对值**：缓存环补「命中 X · 写入 Y tok」副标题，比例条改「未缓存输入」口径（分母不含缓存命中）；缓存命中率全链路本就可用（pi-ai 兜 `cached_tokens`/`prompt_cache_hit_tokens` 两种拼写，第三方端点流式回 usage 即继承）

**终端：**

- 🖥 **node/bash App 域可直跑**：W^X 下 App 域 exec 私有 ELF 与 shebang wrapper 一律 EACCES——launcher 注入 `BASH_FUNC_node%%`/`BASH_FUNC_bash%%` 导出函数通道（bash 启动自动导入，函数体 fork 跑 linker64 不 exec）；node 真 ELF 挪 `lib/node.bin`，`bin/` wrapper 改 `MEOW_RUNTIME_DIR` 定位（$HOME fallback 供 Termux 手测）；App 内终端可直接 `node` 跑脚本，DSH bash 工具子进程同样可用

**文件管理（资源管理器化一批）：**

- ⭐ **收藏抽屉替代快捷栏**：长按收藏文件/文件夹（新收藏置顶），收起露最近 4 个 chip、下拉展开全部 + 根目录切换项；重命名/移动/删除自动同步收藏路径，文件已删的收藏自动失效
- 📋 **长按直复制/移动 + 目录选择器**：双列图标宫格菜单（图标在左文字在右，删除红显，收藏随态切换）、完整绝对路径面包屑（可选工作区上级 filesDir / 点段跳级 / 铅笔输入路径跨根跳转）、逐级进入子文件夹 + 新建文件夹、源目录及其子树锁定防自嵌套
- 🖼 **视图与多选**：宫格（一行三项）/ 瀑布流（一行两项卡片）两种查看方式；顶栏多选开关亮起化（选中项 primaryContainer 高亮 + 勾选框保留）；更多菜单新增「显示隐藏文件」开关（`.` 开头默认隐藏）
- 🔀 **右侧看板快捷文件三模式**：工作区 / 最近使用 / 收藏，标题右侧按钮循环切换

**本包修复：**

- 🐛 **顶栏两行标题被裁**：M3 1.3.0 TopAppBar 内部 `windowInsetsPadding + heightIn(expandedHeight)` 与外层 `Modifier.height(68dp)` 叠加，内容区被压成约 40dp，大标题下半截被裁（双机实测）；删掉外层高度修饰符，两行标题交默认 expandedHeight 垂直居中
- 🐛 **面包屑根段点击原地踏步**：根段 `var` 闭包捕获问题
- 🔧 **设置**：主题对话框内容超高时可滚动（四档单选 + 色卡 + HEX 输入不再互相挤压）

**DSH 侧（闭包 +14 包，runtime.bin 重打 75MB）：**

- 普通模式新增 12 包：`dsh-agent-presets` / `dsh-persona` / `dsh-plan-mode` / `dsh-commands` / `dsh-command-goal` / `dsh-user-questions` / `dsh-tool-ask-user` / `dsh-tool-subagent` / `dsh-tool-subagent-control` / `dsh-subagent-spawn-in-process` / `dsh-command-compact` / `dsh-subagent-in-process-driver`；创造模式补 2 包：`dsh-tool-cordis` / `dsh-cordis-host-runner`
- `meow-jsonrpc.js` 扩展：`presets/list`·`read`·`delete`、`session/command`（斜杠命令通道）、`session/query`（状态水合）、`session.question` 通知 + `session/answerQuestion`（问答通道，连接生命周期管理）、prompt/command 参数携带 `presetId`/`cwd`、resume 按日志重挂预设、MeowRpcError 结构化错误码（PRESET_UNKNOWN/-32001 等五类）；`setProvider` 透传 `compat`（不传 = 清除回自动）、`llm/models` 逐模型附 reasoning 元数据（resolveModelInfo 查表，单条容错）；`{{soul}}`/`{{soul_path}}` 灵魂变量（实时读 SOUL.md）
- fork 补丁：新增 **0002**（Agent 预设支撑 + str_replace_editor 接会话 cwd，跨工作区 str_replace 落点修正）与 **0004**（创造模式两包 + headless 提示词适配段），0001/0003 不变，基线仍为 `dsh-v0.1.1-rc.2`
- 环境注入：`DSH_HOME=filesDir/.dsh`（用户预设根/skills 根迁移）、`DSH_CWD` 按设置的工作区返回；`dsh-presets/` 资产按 sync-token 播种（本版新增 meow-cordis 预设与 `.agents/memory/SOUL.md`）

**验证状态**：普通模式 PC 冒烟 10 项全绿（initialize / presets/list / session/command /plan·/goal / session/query / 错误映射 / 冷 resume 重挂 / 插件 active 清单），真机验证 DSH 子进程存活、并发请求稳定、连接稳定（首装曾因闭包丢 `@img/sharp-wasm32` 致 DSH 子进程启动即崩——sharp 0.35.4 hoisted 挤掉 manifest 钉死的 0.35.3，已修复并固化 dsh-fork 0003）；创造模式真机 RPC 探针通过（播种 / presets list·read / 会话挂载 / plan 切换 / 杀进程 resume 重挂），AI copy-preset 闭环、define→run、视觉诉求路由待主人聊天验收；顶栏修复双机实测；第三方思考强度与缓存绝对值构建通过，后端部分（llm/models 元数据 + setProvider compat）随本次重打闭包生效。完整验收清单见 `plan/plan-standard-mode.md` §八 / `plan/plan-creative-mode.md`。

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.5...v0.2.6
