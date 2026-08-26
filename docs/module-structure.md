# 🗺️ 安卓端模块地图（原子化拆分后）

> 文档状态：✅ 2026-08-17 原子化拆分重构后编写
> 背景：把大杂烩文件按「结构 / 组件 / 逻辑」拆开（类比 HTML 拆出 JS 和 CSS），同包内移动、符号不变、行为零改动，`assembleDebug` 构建通过 + 真机安装验证（com.meow.academy 0.1.0）
> 相关文档：[AGENTS.md](../AGENTS.md)（本地约定）· [README.md](../README.md) · [decision-dsh-agent.md](decision-dsh-agent.md) · [plan-phase1.md](../plan/plan-phase1.md)

---

## 一、拆分思路：HTML / JS / CSS 类比

安卓 Compose 项目里，一个「页面」其实同时装着三类东西，拆分前全挤在一个文件里：

| 类比 | 含义 | 对应物 |
|---|---|---|
| **HTML（结构）** | 页面骨架、组件树怎么摆 | `*Screen.kt` 里的页面组合 composable |
| **CSS（展示）** | 长什么样、可复用的展示组件 | 独立组件文件（气泡 / 抽屉 / 表单 / 卡片）、`ui/theme/` 主题系统 |
| **JS（行为）** | 数据怎么来、逻辑怎么跑 | ViewModel、数据模型、纯函数（序列化 / 解析 / 引擎） |

拆分原则：
- **同包内移动**：拆出去的文件与原来的类/函数在同一个包，`import` 路径不变，调用方零改动；
- **符号不变**：类名、函数名、参数、可见性全部照搬，只重新分组；
- **行为零改动**：只挪代码不重写逻辑，重构后构建通过 + 真机冒烟验证。

---

## 二、包总览

```
app/src/main/java/com/meow/academy/
├── MeowAcademyApp.kt          # Application：全局单例（settingsRepository / runtimeManager）
├── MainActivity.kt            # 入口 Activity
├── ui/                        # Compose 界面层
│   ├── MainScreen.kt          #   底部导航骨架 + 终端全屏覆盖
│   ├── theme/                 #   主题系统（浅色/深色/跟随系统/Material You）
│   ├── chat/                  #   聊天页（结构/组件/模型/纯函数分文件）
│   ├── settings/              #   设置页 + 模型管理页
│   ├── terminal/              #   终端页（连接层 + ANSI 引擎分离）
│   └── files/                 #   文件管理页（列表/搜索/目录导航 + 统一编辑器：文本/Markdown/HTML 预览与编辑）
├── data/                      # 数据层
│   ├── chat/                  #   Room：会话 + 消息两表
│   └── settings/              #   DataStore：设置项 + 枚举 + 展示名映射
├── rpc/                       # DSH JSON-RPC 协议层（客户端 + 帧/参数/事件/模型）
└── runtime/                   # DSH 运行时（解压 / 拉起 / 前台服务 / 心跳保活）
```

---

## 三、模块明细

### rpc/ — DSH 协议层（最底层，无 Compose 依赖）

| 文件 | 职责 | 被谁用 |
|---|---|---|
| `DshRpcClient.kt` | JSON-RPC 2.0 客户端：stdout JSONL 读循环、按 id 路由响应、通知广播、请求封装（initialize / prompt / cancel / setModel / bash / ping / llm / settings 系列） | ChatViewModel / ModelManageViewModel / DshRuntimeService / DshKeepAliveWorker |
| `DshConnectionState.kt` | 连接状态机（Connecting / Running / Closed） | 客户端自身 + 上层流式收集兜底 |
| `DshFrames.kt` | 协议帧模型（DshRequest / DshResponse / DshError / DshNotification）+ 常量表（通知方法 / 事件类型 / chunk 类型 / turn 结束原因） | 客户端、ChatViewModel |
| `DshParams.kt` | 请求参数构造器（协议方法的 params JsonObject 构建） | 客户端（间接） |
| `DshEvent.kt` | 服务端推送通知的解析视图（session.event / session.status / session.bashOutput 的常用访问器） | 客户端广播、ChatViewModel |
| `LlmModels.kt` | 模型管理数据模型（LlmProviderInfo / LlmModelInfo / LlmModelInput） | 客户端、模型管理页 |
| `DshJson.kt` | JsonObject 便捷扩展（str / bool / int，绝不抛异常） | rpc 包 + ui 层共用 |

### ui/chat/ — 聊天页

| 文件 | 角色（HTML/JS/CSS 类比） | 职责 |
|---|---|---|
| `ChatScreen.kt` | HTML 结构 | 页面骨架：抽屉 + Scaffold + 消息列表组装（原 892 行瘦身到 201 行） |
| `ChatViewModel.kt` | JS 行为 | 会话 CRUD、发送消息、流式收集（session.event）、停止生成、模型切换 |
| `ChatSegment.kt` | JS 数据模型 | Segment（思考/文本/工具）+ ToolCallInfo + StreamingState，UI 与 VM 共享 |
| `ChatSegmentJson.kt` | JS 纯函数 | segmentsJson 序列化/解析、delta 增量追加（appendReasoning / appendText） |
| `MessageBubbles.kt` | CSS 组件 | 消息气泡家族：用户/助手气泡、思考卡片、工具调用组与卡片 |
| `ChatInputBar.kt` | CSS 组件 | 输入栏 + 工具栏（provider/模型/思考强度下拉 + 联网开关 + 上传） |
| `SessionDrawer.kt` | CSS 组件 | 会话抽屉 + 重命名/删除对话框 |
| `MarkdownText.kt` | CSS 组件 | Markwon Markdown 渲染（可复用组件；流式走块级半增量渲染；收集 appconfig 渲染配置并随主题/配置重建） |
| `MarkdownStreaming.kt` | JS 纯函数 | 流式块拆分器：稳定块 + 活动块（splitStreamingBlocks / isTableDelimiter） |
| `DollarMath.kt` | JS 纯函数 | 单 `$…$` 行内公式匹配（matchDollarMath，供 DollarMathInlineProcessor 调用） |
| `StreamingMarkdownRenderer.kt` | JS 渲染缓存 | 稳定块 Spanned LRU 缓存 + 稳定前缀复用 + 活动块重渲染 |
| `MarkdownMarkwon.kt` | CSS 构建器 | Markwon 全插件实例（表格/链接/代码着色 Prism4j/LaTeX 公式）+ 公式块圆角背景配置 + PrismBundle 声明 |
| `MarkdownConfigPlugin.kt` | CSS 构建器 | 把 MarkdownConfig 应用到 Markwon 主题（· 大小/颜色、引用/链接/标题/分割线）+ 注册圆角代码块 Span 工厂 |
| `RoundedCodeBlockSpan.kt` | CSS 组件 | 圆角代码块 Span（整块一个圆角，不是每行）+ SpanFactory |

### ui/settings/ — 设置页 + 模型管理页

| 文件 | 角色 | 职责 |
|---|---|---|
| `SettingsScreen.kt` | HTML 结构 | 设置页主体（默认首页/主题/常驻/终端/模型入口） |
| `SettingsViewModel.kt` | JS 行为 | DataStore Flow → StateFlow + 写操作 |
| `SettingsComponents.kt` | CSS 组件 | 通用组件：SectionHeader / SettingsRow / SingleChoiceDialog |
| `SettingsDisplayNames.kt`（data/settings） | CSS 展示映射 | 枚举 → 展示名（HomeTab / ThemeMode / ResidentMode） |
| `ModelManageScreen.kt` | HTML 结构 | 模型管理路由（列表 ↔ 详情）+ 列表页 |
| `ProviderDetailScreen.kt` | HTML 结构 | 详情页：状态编排 + 页签 + 对话框调度 |
| `ProviderForms.kt` | CSS 组件 | 内置 DeepSeek 配置（BuiltinConfig）+ 自定义 provider 表单（ConfigTab） |
| `ModelListTab.kt` | CSS 组件 | 模型列表页签 + 模型卡片 |
| `ModelManageDialogs.kt` | CSS 组件 | 4 种对话框：删除确认 / 添加模型 / 编辑模型 / 获取到模型列表 |
| `ModelManageViewModel.kt` | JS 行为 | provider 目录/profile 读写、保存/删除/发现/测试模型、默认星标 |
| `ModelManageModels.kt` | JS 数据模型 | ProviderProfile / ModelProfile / ProviderListItem + slug / PRESETS / DEEPSEEK_PROVIDER |

### ui/terminal/ — 终端页

| 文件 | 角色 | 职责 |
|---|---|---|
| `TerminalScreen.kt` | HTML 结构 | 终端页：标题栏 + 屏幕渲染 + 快捷命令 + 输入框 |
| `TerminalViewModel.kt` | JS 连接层 | PTY unix socket 连接、状态流、输入/中断/清屏（只留 I/O，屏幕逻辑已拆走） |
| `AnsiScreen.kt` | JS 引擎 | **纯逻辑**屏幕缓冲 + ANSI/VT100 解析（SGR 颜色 / CUP 光标 / ED 清屏），无 Android 依赖可单测 |
| `TerminalSegment.kt` | JS 数据模型 | 渲染段（文本 + 前景色），Screen 与 VM 共享 |

### data/ 与 runtime/（未拆分，保持单职责）

- `data/chat/` — Room：`ChatDatabase` / `ChatDao` / `ChatEntities`（会话+消息，`segmentsJson` 存步骤序列）
- `data/settings/` — DataStore：`SettingsRepository`（主题/默认首页/常驻/模型配置）+ `SettingsEnums` + `JsoncConfig`（JSONC 通用管道 stripJsonc / parseConfigJsonc / deepMerge）+ `MarkdownConfig`（JSONC 渲染配置数据类/解析器）+ `MarkdownConfigRepository`（config-defaults 同步 + appconfig/markdown-config.jsonc 用户覆盖 + FileObserver 热更）
- `data/model/` — 模型目录缓存（前后端解耦）：`ModelCatalogRepository`（DataStore 存 settingsDescribe result JSON）+ `ModelCatalog`（ProviderProfile/ModelProfile 模型与解析）
- `runtime/` — `RuntimeManager`（状态机 + Mutex）、`DshRuntimeService`（前台服务 + initialize 握手）、`DshProcessLauncher`（linker64 拉起）、`RuntimeExtractor`（解压 assets）、`DshKeepAliveWorker`（心跳 ping）、`AppLifecycleObserver`

---

## 四、协作关系（关键链路）

```
聊天链路：
ChatScreen(骨架) ──▶ ChatViewModel ──▶ DshRpcClient.prompt() ──▶ DshParams/DshFrames
      │                    │                    │
      ├─ SessionDrawer     ├─ ChatSegment(模型)  └─▶ 事件流 session.event
      ├─ MessageBubbles    ├─ ChatSegmentJson(序列化)       │
      └─ ChatInputBar      └─ Room(ChatDao) ◀──────────────┘
                                    │
                            MessageEntity.segmentsJson

终端链路：
TerminalScreen ──▶ TerminalViewModel(PTY socket) ──▶ AnsiScreen(ANSI 解析) ──▶ TerminalSegment 渲染

模型管理链路：
ModelManageScreen ──▶ ModelManageViewModel ──▶ DshRpcClient(llm/settings 方法) ──▶ LlmModels
        │                        │
        ├─ ProviderDetailScreen  └─ ModelManageModels(共享数据)
        └─ ProviderForms/ModelListTab/ModelManageDialogs

Markdown 渲染配置链路（2026-08-24 JSONC 化）：
config-defaults/markdown-config.jsonc(默认模板) + appconfig/markdown-config.jsonc(用户覆盖)
        ──▶ MarkdownConfigRepository(深合并 + FileObserver 热更)
        ──▶ StateFlow<MarkdownConfigRaw> ──▶ MarkdownText(resolveMarkdownConfig 按主题解析)
        ──▶ buildMarkwon(MarkdownConfigPlugin + 公式块圆角配置) ──▶ Markwon 渲染
```

## 五、给未来开发者的扩展指引

- **加一种新的消息步骤类型**：改 `ChatSegment.kt`（Segment 密封类）+ `ChatSegmentJson.kt`（序列化/解析/增量）+ `MessageBubbles.kt`（渲染分支），三处同步即可；
- **加一个可配置 provider**：`ModelManageModels.kt` 的 `PRESETS` 加一行即可出现在列表；`DshParams.kt` / `DshRpcClient.kt` 加对应 llm/settings 方法；
- **加 ANSI 渲染能力**：改 `AnsiScreen.kt`（纯逻辑，可在 JVM 上写单测）；
- **新协议方法**：`DshParams.kt`（参数）→ `DshRpcClient.kt`（封装）两步走，帧/常量放 `DshFrames.kt`。

## 六、验证

```bash
cd android-app && ./gradlew assembleDebug   # 构建通过（重构验证）
```
