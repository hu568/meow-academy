# 🐾 喵仓动态化架构探索笔记

> 日期：2026-08-20 · 主题：DSH 智能后端 + 原生 App 的"可热更 / AI 可编排"架构
> 一句话总结：**把 App 拆成「原生通用渲染器」+「数据/脚本驱动层」，让 UI 和功能都由可热更的内容说了算。**

---

## 0. 背景：我们现在有什么（MeowAcademy 现状）

- **前端**：Kotlin + Jetpack Compose + Material 3（原生 UI）
- **后端**：内置 DeepSeek Harness（DSH）运行时，跑在 App 内嵌 Linux 容器里
- **通信**：本地 socket **JSON-RPC 2.0**（非 HTTP），`meow-jsonrpc` 插件扩展了官方接口
- **常驻**：三档保活策略 + 前台服务 + WorkManager/JSON-RPC 心跳（后台不死的保障）
- **持久化**：设置走 **DataStore Preferences**；会话走 Room(SQLite)
- **里程碑**：M1~M3 完成（后端、套壳、真终端、会话持久化）；M4 知识库 / M5 RAG 待做

**核心链路**：App 启动 → 解压 runtime.bin → linker64 拉起 node 跑 terminal-host.js（内置 PTY bash）→ DSH 作为 bash 子进程 → 聊天/终端经本地 socket 走 JSON-RPC → 模型请求发往云端 API（DeepSeek / 自定义 Provider）。

---

## 1. 核心心智模型：两个角色分工

| 角色 | 干什么 | 用谁 |
|------|--------|------|
| **施工队（宿主）** | 真的动手画 UI、做动画、调系统 | **Kotlin / 原生** |
| **军师（脚本/智能）** | 出方案：颜色、动画、行为、规则 | **JS / DSH 插件** |

> 关键比喻：CSS 是"装修方案书"（文本描述），Kotlin 是"施工队"（亲手执行）。
> JS/DSH 是发号施令的大脑，Kotlin 是动手实现的拳头。各司其职，不抢对方活。

---

## 2. 热更新的"上限"取决于哪层

**改 JS 的上限 = 你的 Kotlin 代码愿意让 JS 控制多少（预留接口的边界）。**

| 能力 | JS/DSH 能热更吗 |
|------|:---:|
| 主题色 / 字体 / 动画参数 | ✅ 轻松 |
| 控件显隐 / 顺序 / 文案 / 图片 | ✅ 轻松 |
| 业务规则 / 逻辑参数（折扣、概率…） | ✅ 轻松 |
| 拼接已有模块成新页面 | 🟡 可以（用容器组件）|
| **从零造一个新的原生控件** | 🔴 很难（原生代码烤死进 APK）|
| 碰系统 / 文件 / Root 敏感能力 | 🔴 沙箱不开放 |

**结论**：想让功能能热更，就要**把功能尽量往 JS/插件层设计**；原生层只做"通用渲染器"。

---

## 3. TS vs Kotlin 的角色定位

- **TS 的"编译" = 翻译成 JS** → 产物能在运行时被读 → **天生适合当可热更脚本**
- **Kotlin 的"编译" = 烤进 APK** → 改 Kotlin 必须重新发布 → **天生当宿主施工队**
- 二者是**搭档不是对手**：Kotlin 当底盘（画 UI），TS/JS 当大脑（出方案）

> 简单脚本直接用 JS 更省事（省一道编译）；脚本复杂、要类型安全时再上 TS（构建时编成 JS 再下发）。

---

## 4. DSH 到底是什么 & 竞品分析

- **DSH = DeepSeek Harness**：给 AI 配一个"能动手操作的运行时"（执行 bash、读写文件、改前端、热更新）。
- 前端热更关键词 **HMR（Hot Module Replacement）**。
- 参考项目 `deepseek-harness-mobile`（App 名"深度编码"）：
  - **本质 = WebView 套壳 + 内嵌 Debian rootfs 容器**，AI 在容器里跑 bash 改网页代码
  - 工程功夫在"壳"：80MB 单 APK、proot 兼容、manifest 驱动热更新（SHA-256 校验 + 回滚）、Shizuku 保活、SAF 目录桥
  - **并非"原生 UI 动态化"**，前端是整棵网页
- **定位对比**：它在做"通用 AI agent 运行时"；MeowAcademy 在做"原生 App + 智能后端"。**它不是我们的直接竞品**，反而验证了"宿主 + 脚本 + 留接口 + 热更新"这套方向是对的。

**可借鉴的设计**：
- 桥协议（`window.androidBridge`）—— JS ⇄ 原生互通
- WebView 安全边界（只放行引擎同源，其余跳系统浏览器）
- 热更新：下载 → 校验 → 原子切换 → 回滚 → 自动重启

---

## 5. 桌宠 & 悬浮窗（含后台运行）

**分两层**：

| 部分 | 依赖层 | 能热更吗 |
|------|--------|:---:|
| ① 形象 / 动画 / 悬浮窗 | 原生 `WindowManager` + 渲染器 | 资源可热更 |
| ② 智能大脑（对话/行为/性格） | DSH 插件 | ✅ 完全热更 |

**关键点**：
- **悬浮窗** = `WindowManager` 的 `TYPE_APPLICATION_OVERLAY`，可盖在别的 App / 桌面，App 后台也运行
- 悬浮窗里可以放**透明 WebView**（`webView.setBackgroundColor(Color.TRANSPARENT)` + html `body{background:transparent}`）→ 借 WebView 白嫖 CSS/JS/SVG 动画 + 热更
- **悬浮窗权限**：`SYSTEM_ALERT_WINDOW`，必须用户到系统设置手动授权（绕不开）
- **后台保活**：复用现有前台服务 + 心跳机制（国产 ROM 要做保活策略表）
- 桌宠 = "原生通用渲染器（悬浮窗+透明WebView） + DSH 智能插件（性格/行为/AI）"

**落地组合拳**（是最小 demo 起点）：
```
WindowManager.addView(透明WebView加载pet.html, TYPE_APPLICATION_OVERLAY 可拖拽)
pet.html 放 filesDir（可热更）
DSH meow-pet 插件 经 JSON-RPC 下发动作指令
```

---

## 6. JSON 驱动设置页（含 AI 改设置）

**现状**：`SettingsRepository.kt` 是标准硬编码 + DataStore —— 每个设置项手写 `Key + 读 Flow + 写方法`，全写死，增加项要重编译发版。

**目标**：数据驱动 UI（Schema-Driven UI），让设置页结构可热更、AI 可改。

### 关键设计原则（今天最重要的一条教训）
- ❌ **不要写一个大 JSON 文件**（20多个大项堆一起是维护噩梦）
- ✅ **按一级模块拆分多文件** + **Schema(界面描述) 与 Values(当前值) 分离**

```
filesDir/
  settings-schema/            ← 界面描述（UI 结构），分模块热更
    appearance.schema.json
    chat.schema.json
    model.schema.json
    resident.schema.json
    ...
  settings-values/            ← 当前值（用户/AI 改这里）
    appearance.values.json    = { "dark": true, "accent":"#FF5722" }
    model.values.json         = { "provider":"deepseek", ... }
    ...
      （或先用 DataStore，以后迁 values 文件）
```

### 为什么 schema 和 values 分离
- Schema 很少变（UI 结构），Values 经常变（用户/AI 改值）
- 热更界面 = 换 schema；改设置 = 改 values，互不干扰
- AI 改 values 不改 schema，篡改面小更安全

### 通用 FormRenderer（写一次，画所有）
```
appearance.schema.json ─┐
chat.schema.json        ├─► 通用 FormRenderer（按大项分组渲染）
model.schema.json       ─┘     · 每份 schema = 一个大设置项
                                · 读 schema → 画控件 → 回写 values
```

### AI 改设置
- 方案 A：AI 走 JSON-RPC 接口 `setSetting(key, value)` → 调现有 Repository setter
- 方案 B：AI 直接 `write` values JSON 文件 → 热生效（与 DSH 的 read/write 工具最契合）
- **安全边界**：AI 只能改"体验类"（主题/强度/偏好），**不能碰** API_KEY / credentials / 权限
- 可回滚（restoreDefaults）+ 让用户感知"这是 AI 改的"

---

## 7. 这座宫殿的"开放性"全景（今天最大的顿悟）

**DSH 开放 AI 运行流程 + App 开放表现层 = 双自由度可组合的开放**

| 开放维度 | 能改 | 热更 |
|---------|------|:---:|
| AI 流程 | Agent 思维/工具/loop | ✅ |
| App 外观 | 主题/颜色/动画 | ✅ |
| App 功能 | 桌宠/工具/新玩法 | ✅ |
| App 界面结构 | 设置页/菜单/列表 | ✅ |
| App 数据 | 知识库/内容 | ✅ |
| 扩展方式 | 加插件/加 JSON/加 HTML | ✅ |

**共同底层哲学**：把"逻辑"从"硬编码"里解放出来，变成"可读写的脚本/数据"——谁掌握这个，谁就掌握"任意演化"的钥匙。

**但：开放 ≠ 失控**。要做好护栏：
- 权限分层、白名单（AI 不能碰密钥/权限）
- 可回滚快照
- 插件隔离、版本管理
- 明确"哪些可数据化、哪些保持原生"（别为 JSON 而 JSON）

---

## 8. 建议的落地优先级（柿子先挑软的捏）

| 顺序 | 做什么 | 为什么先做 |
|:---:|--------|-----------|
| 1️⃣ | JSON 驱动设置页（多 schema 拆分）| 低风险、见效快、打通"热更+AI改"的基础 |
| 2️⃣ | 悬浮窗 + 透明 WebView + 桌宠最小 demo | 验证悬浮窗/热更/后台保活全链路，最带感 |
| 3️⃣ | DSH 插件扩展（AI 流程开放）| 把智能层做厚 |
| 4️⃣ | RAG 知识库（M4/M5）| 核心差异化功能，与 WebView 铺路配合 |

**警惕点**：
- 别把核心聊天页 WebView 化（保持原生稳），悬浮层只当"活的应用"
- 别过度设计"全 JSON 化"，简单高频的保原生

---

## 9. 待办 / 下一步行动
- [ ] 设计 `settings-schema/*.json` 结构与 FormRenderer 接口
- [ ] 设计 `setSetting` JSON-RPC 接口签名 + DSH 侧接入
- [ ] 悬浮窗 + 透明 WebView 最小 demo（悬浮权限 + 拖拽 + pet.html）
- [ ] meow-pet 插件（性格/行为/AI 对话）最小结构
- [ ] 相关参照：`deepseek-harness-mobile` 的 AndroidBridge / 热更新校验 / WebView 安全边界

---

## 10. 落地案例：Markdown 渲染配置（appconfig/markdown-config.js）

把「施工队 + 军师」落到第一个真实功能：**JS 控制 Markdown 渲染外观**（2026-08-21）。

### 10.1 文件与契约
- `appconfig/markdown-config.js`：JS 配置（最后一行赋值全局 `markdownConfig`）
- Kotlin 用 Rhino 解释模式求值 → `MarkdownConfig` 数据类 → 应用到 Markwon 主题 / 公式渲染
- 修改文件后 FileObserver 热更，无需重启；DSH/AI 也可经 write 工具改写（体验类配置，安全）

### 10.2 已开放的能力
| 配置键 | 作用 |
|--------|------|
| `formula.blockCornerRadiusDp` / `blockBackground` / `blockPaddingDp` | 公式块圆角背景 / 内边距 |
| `formula.blockAlign` / `blockFitCanvas` | 公式块对齐 / 撑满 |
| `list.bulletWidthDp` / `bulletStrokeWidthDp` / `itemColor` | `-` 渲染的 · 大小 / 颜色 |
| `code.blockCornerRadiusDp` / `blockBackground` / `blockMarginDp` | 代码块圆角背景 / 边距 / 字号 |
| `quote` / `link` / `heading` / `thematicBreak` | 引用 / 链接 / 标题 / 分割线 |

### 10.3 热更路径
```
AI/用户 → DSH write 工具 → 写 markdown-config.js
  → FileObserver 感知 MODIFY
  → Rhino 重新求值 → StateFlow 更新
  → MarkdownText 重组 → 新 Markwon 实例 → 下次渲染生效
```

### 10.4 真机验证记录（2026-08-21 ✅）

**验证目标**：AI agent 直接改 JS 就能让前端渲染效果实时生效，全程无需重编译 / 重启 App。

**验证步骤**（DSH 内置终端里让 agent 操作）：
1. agent 用 `write` 工具把 `appconfig/markdown-config.js` 里的 `list.bulletWidthDp` 从 `6` 改成 `12`，并新增一段多行代码块（验证「整块一个圆角」）；
2. agent 用 `read` 工具回读确认写入成功；
3. 回到聊天页查看：**`-` 渲染的 `·` 变大了、多行代码块是整块圆角（而非每行独立圆角）** —— 无需重启、无需重新安装 APK。

**结论**：
- ✅ FileObserver → Rhino → StateFlow → Markwon 重建的整条热更链路真机走通；
- ✅ 「AI 编排前端效果」闭环成立：**AI 只改 JS（装修方案书），Kotlin 施工队自动重建渲染**，正是本文档「施工队 + 军师」心智模型的第一次完整落地；
- ✅ 安全边界符合预期：改的是 `appconfig/markdown-config.js`（体验类白名单），未涉及密钥 / 权限。

**过程中的 Bug 修复记录**：
- 🔧 代码块圆角最初是「每行一个圆角」（`LeadingMarginSpan.drawLeadingMargin` 会按行调用）；
- 🔧 修复：`RoundedCodeBlockSpan` 只在第一行（`!first` 就 `return`）绘制一次整块圆角矩形，用 `Layout.getLineForOffset` 算整块首/末行高度，并把圆角半径钳制到 `≤ 块高/2` 防单行重叠 —— 现已真机验证多行代码块是整块一个圆角。

---

> 📌 关联文件：`md_test.md`、`plan-phase3.md`（历史规划）
> 🧭 本笔记对应仓库：github.com/hu568/meow-academy
