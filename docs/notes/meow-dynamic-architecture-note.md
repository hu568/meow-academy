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

### 5.1 聊天页 WebView 背景（同思路扩展）

桌宠的「原生通用渲染器 + 可热更 HTML + AI 改写」思路，可以直接复用到聊天页背景：

- **形态**：聊天页底部垫一个透明 WebView（`webView.setBackgroundColor(Color.TRANSPARENT)`），HTML 负责画背景（渐变 / 粒子 / 动态光效）；
- **热更**：AI 改 `appconfig/chat-background.html` → FileObserver 感知 → WebView reload，无需重启 App；
- **分层**：
  - JSON 配置：控制 WebView 是否启用、透明度、模糊强度等参数（`config-defaults/` 放默认，`appconfig/` 放用户覆盖）；
  - HTML/CSS/JS：背景内容本身（AI 直接改）；
  - DSH 插件：自动换背景、定时主题等行为逻辑；
- **安全**：复用 HTML 预览的安全配置——开启 JS，但关闭 `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`，防页面内 JS 越权读 App 文件；
- **性能**：聊天页不可见时暂停/停止 WebView 动画，避免常驻空转耗电。

> 💡 桌宠、聊天页背景、未来的自定义面板 = 同一套「WebView 热更基建」的三个落地场景。

### 5.2 资源热替换（图片 / 音频 / 字体 / Lottie 等）

文本配置走「默认模板 + 用户覆盖」，**文件型资源走「覆盖优先」**：

- **不复制默认资源到 `config-defaults/`**：二进制文件 AI 没法文本编辑，只能整体替换；
- **资源按类型放 `appconfig/` 下的子文件夹**：
  ```
  appconfig/fonts/     ← 字体（.ttf/.otf）
  appconfig/images/    ← 图片（.png/.webp/.gif）
  appconfig/audio/     ← 音频（.mp3/.ogg/.wav）
  appconfig/lottie/    ← Lottie 动画（JSON，AI 可改内容，但按资源文件管理）
  ```
- **JSONC 配置负责引用**：比如 `resources.jsonc` 或各功能 JSONC 里写 `"background": "images/bg.jpg"`、`"sound": "audio/notification.ogg"`、`"petAnimation": "lottie/pet.json"`；
- **运行时逻辑：有就用，没有就回退内置默认**：
  ```
  appconfig/images/bg.jpg   存在 → 用它
  appconfig/images/bg.jpg   不存在 → 用内置默认（APK 资源）
  ```
- **AI 能力**：二进制只能整文件替换（上传/复制）；Lottie/JSON 这类文本资源可以读/复制/改；
- **校验与回退**：字体魔数、图片解码、音频格式、Lottie JSON 合法性，失败一律回退内置默认；
- **App 升级不碰 `appconfig/`**，自定义资源全部保留。

> 📌 资源热替换 = 「JSON 配置引用 + 文件覆盖优先」；文本配置 = 「默认 + 覆盖合并」模式。两者分开记，别混。

### 5.3 字体与字号热更（JSON 配置 + fonts 文件夹）

字体和字号统一用一份 JSON 配置，字体文件单独放文件夹：

```
config-defaults/typography.jsonc   ← 默认排版模板（文本，走「默认 + 覆盖合并」）
appconfig/typography.jsonc         ← 用户覆盖（只写改过的项）
appconfig/fonts/                  ← 导入的字体文件（二进制，走「覆盖优先」，不复制默认到 config-defaults）
```

JSONC 结构示例：

```jsonc
// 🐾 排版配置覆盖文件，只写想改的键
{
  "version": 1,
  "fontFamily": null,          // null/"default" → 系统默认；"custom.ttf" → 用 appconfig/fonts/custom.ttf
  "sizes": {
    "bodyLarge": 16,           // 全局正文
    "bodyMedium": 14,
    "titleLarge": 22,
    "markdownBase": 15,        // 聊天 Markdown 基础字号
    "codeRatio": 0.85,         // 行内代码/代码块相对比例
    "terminal": 13
  }
}
```

规则：

- `fontFamily: null` / `"default"` → 系统默认字体；
- `fontFamily: "custom.ttf"` → 使用 `appconfig/fonts/custom.ttf`；
- 指定字体**不存在 / 魔数校验失败** → 回退系统默认；
- 字号统一从 JSON 读取，`Type.kt`、Markdown 基础字号、终端/编辑器不再硬编码；
- 热更：FileObserver 同时监听 `typography.jsonc` 与 `appconfig/fonts/` 目录，变化后重建 Typography / Markwon。

> 📌 这属于「文本配置（JSON）+ 资源替换（字体文件）」的组合模式：配置走合并，字体文件走覆盖优先。

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

> ⚠️ 本节为**旧版 JS 方案**（2026-08-21 落地，已真机验证）。2026-08-24 已按 §11 迁移为 **JSONC + 默认模板/用户覆盖**（`config-defaults/` + `appconfig/markdown-config.jsonc`），旧 `.js` 仅在存量升级时一次性迁移。历史记录保留供追溯。

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

---

## 11. Markdown 配置更新问题：默认模板 + 用户覆盖（讨论中）

> 日期：2026-08-24 · 主题：解决「开发者更新可修改项后，用户自定义配置被覆盖 / 享受不到新项」的矛盾
> 一句话总结：**把「内置默认模板」和「用户覆盖」拆开，运行时深合并；存量用户旧文件整体当作覆盖，零迁移零丢失。**

### 11.1 问题背景

- 现状：`appconfig/markdown-config.js` 一个文件同时承担「内置默认模板」和「用户覆盖值」。
- 首次启动从 assets 播种，**已有文件不覆盖** → App 升级后新模板进不来。
- 后果：
  - 新增的可修改项不会出现在用户文件里 → 用户看不到 / 没法改；
  - 开发者调整旧键默认值时，用户文件里的旧值会盖住新默认 → 存量用户享受不到新默认。
- 根因：**默认值与用户值混在一个文件，更新时无法区分「用户真改过」和「只是旧默认值」**。

### 11.2 方案：默认模板与用户覆盖分离 + 深合并

> 路径说明：
> - `assets/config-defaults/...` = **源码/APK 内 assets**（`android-app/app/src/main/assets/config-defaults/`），构建时打进 APK，运行时只读；
> - `config-defaults/...` = **运行时默认模板目录**（`context.filesDir/config-defaults/`），从 assets 播种，供 DSH/AI 读取，随版本刷新；
> - `appconfig/...` = **运行时用户配置目录**（`context.filesDir/appconfig/`，由 `RuntimeExtractor.appConfigDir()` 创建），用户/AI 在这里改文件。

```
assets/config-defaults/markdown-config.jsonc   ← 内置默认模板（APK assets，只读，随 App 更新）
config-defaults/markdown-config.jsonc          ← 默认模板副本（运行时，仅 APK 升级/同版本重装时从 assets 同步；AI 可读可复制，不可修改）
appconfig/markdown-config.jsonc                ← 用户文件（默认 = 模板完整副本，用户/AI 直接改值，App 更新永不碰它）
```

> 📌 两个目录下文件名相同，复制粘贴方便；`config-defaults/` 是通用默认目录，以后所有功能的默认设置都可以放这里。
> 📌 **配置格式统一 JSONC**（JSON + `//`、`/* */` 注释）：Kotlin 侧先 `stripJsonc()` 剥注释，再按标准 JSON 解析；不引第三方依赖，保持严格 JSON 规范。

运行时：

```js
merged = deepMerge(defaults, overrides)
// 对象递归合并；数组/标量整体替换
```

**回退链**（天然成立，不用额外写逻辑）：

```
用户覆盖缺失 / 为 null
        ↓
默认模板（config-defaults/）
        ↓
Kotlin 内置默认
```

- 用户配置不存在 / 是 `{}` → 纯默认模板；
- 用户配置少了某个键 → 用默认模板里的值；
- 默认模板里也没有 → 用 Kotlin 数据类默认值；
- 更新引入新配置项 → 默认模板有、用户覆盖没有 → 自动用新默认值；
- `null` 语义约定为「回退默认」，与「没写」等价（沿用现有 markdown-config 的行为）。

效果：

| 项目 | 效果 |
|---|---|
| 用户自定义 | ✅ 全部保留 |
| 新增可修改项 | ✅ 自动出现（默认有、覆盖无 → 合并进来） |
| 删除可修改项 | ✅ 自动消失（解析器只认已知键） |
| 旧键默认值更新 | ⚠️ 对存量用户不生效（保守路线代价） |
| 新安装用户 | ✅ 从干净默认模板开始 |

### 11.3 已确认决策

1. **用户文件路径**：`appconfig/markdown-config.jsonc`（由原 `.js` 迁移而来），默认 = `config-defaults/` 模板的完整副本；用户/AI 直接改值，也可精简为「只留改过的键」。
2. **默认模板目录**：新增通用默认目录 `config-defaults/`（`context.filesDir/config-defaults/`），与用户配置 `appconfig/` 平级分离；以后所有功能的默认设置都可以放这里，不限于 `appconfig/`。
3. **文件名相同**：默认模板与用户文件都叫 `markdown-config.jsonc`，靠目录区分（`config-defaults/` vs `appconfig/`），复制粘贴方便。
4. **存量迁移策略：保守全量保留**——旧 `.js` 文件整体转为用户文件内容，不做 diff、不重写用户文件、不需要历史快照。
   - 好处：用户数据零丢失、实现最简单；
   - 代价：旧文件里的旧键值会盖住新默认值；后续可加「恢复默认」功能解决。
   - 注：当前仅 4 个用户，也可选择手动迁移/让用户重新配置。
5. **新安装用户 seed**：把 `config-defaults/markdown-config.jsonc` 完整复制到 `appconfig/markdown-config.jsonc`（默认状态下两个文件内容相同），保留「用户文件 = 模板副本」的直觉；`version` 也一起复制，记录基于哪个模板版本。
6. **`config-defaults/` 权限与同步策略**：**仅在 APK 升级或同版本重装时从 assets 同步**——启动时读轻量标记（如 `config-defaults/.sync-token`，记录 `versionCode + lastUpdateTime`），一致则跳过，不一致才同步，**不影响启动速度**；**内置 agent 不可修改**，但可以读取、可以复制（例如复制到 `appconfig/` 作为覆盖起点）。
7. **配置格式统一 JSONC**：所有文本配置（markdown / typography / resources 等）用 `.jsonc`（JSON + `//`、`/* */` 注释）；Kotlin 侧 `stripJsonc()` 剥注释后按标准 JSON 解析，不引第三方依赖，保持严格 JSON 规范。

### 11.4 待补充 / 待确认细节

- [ ] `config-defaults/` 同步标记字段：`versionCode + lastUpdateTime` 在同版本重装时是否可靠变化，需真机验证；若不可靠再换方案（如比对 assets 文件哈希）；
- [x] 恢复默认功能已确认：见 §11.5；
- [x] 文件头注释模板已确认：统一一套（功能名 + 显式指向 `appconfig/README.md` + 元数据），不分默认/用户两版；
- [x] `README.md` 内容：各文件用途、可改/只读、复制到 `appconfig/`、恢复默认操作说明；`config-defaults/README.md` 为权威版，**同步一份到 `appconfig/README.md`**（更新时**强制覆盖**，不保留本地修改）；
- [x] 深合并精确语义已确认：对象递归、数组/标量整体替换、`null` = 回退默认（与「没写」等价）；
- [x] 配置格式已确认：统一 JSONC（`.jsonc`），Kotlin 侧 `stripJsonc()` 剥注释后按标准 JSON 解析；
- [x] 是否观察 `config-defaults/markdown-config.jsonc` 副本变化（默认模板由 App 控制，一般不需要热更）→ **不观察**，仅观察 `appconfig/` 用户文件；
- [x] `stripJsonc()` 的实现与单元测试（处理字符串内的 `//`、`/* */`、转义引号）；
- [x] 版本号 / 可修改项数量已定：顶层 `"version"`（ISO 8601 时间戳）+ `"_editableCount"`（`_` 前缀元数据，解析忽略）；详见 `docs/design-dynamic-config.md`；
- [x] `config-defaults/README.md` 可编辑地图：**要做**，大致说明每个文件能改什么（可改/只读、示例）；
- [x] **AI 硬边界**：DSH 工具层 `deny config-defaults/`，内置 agent 不可修改默认模板；
- [x] **资源上传**：内置 agent 有 bash 权限，直接拷贝文件到 `appconfig/fonts/`、`images/` 等即可，不需要专门接口；
- [ ] **git/diff 对比想法**：让 AI 通过对比用户覆盖与默认模板来理解「改了什么」——需确认 runtime 是否带 `diff`/`git`；没有的话可考虑 JSON-RPC 提供 diff，暂缓；
- [ ] **统一配置仓库**：目前仅 markdown 一个配置，暂不抽象；等第 2 个 JSONC 配置出现再抽通用 `ConfigManager`（YAGNI）；
- [ ] 开发者新增/删除可修改项的完整流程文档（改 Kotlin 数据类 + 默认模板 + bump 版本）；
- [ ] `config-defaults/` 除 markdown 外还要放哪些默认设置（后续功能扩展规划）。

### 11.6 落地情况（2026-08-24）

- ✅ **markdown-config 已从 JS 迁移到 JSONC**：`JsoncConfig.kt`（stripJsonc / parseConfigJsonc / deepMerge）+ `MarkdownConfigRepository` 重写（config-defaults 同步 + appconfig 用户文件 + 深合并）；
- ✅ **存量 `.js` 一次性迁移**：检测到 `appconfig/markdown-config.js` 且无 `.jsonc` 时，用 Rhino 求值整体转成 JSONC 用户文件后删除旧文件；
- ✅ **`.sync-token`**（`versionCode + lastUpdateTime`）控制 config-defaults 仅在包更新时同步；`appconfig/README.md` 每次同步时强制覆盖为 `config-defaults/README.md`；
- ✅ **AI 硬边界**：cordis.yml fs-local `deny config-defaults/`（目录前缀匹配）+ persona 提示；
- ✅ **单测**：`JsoncConfigTest`（stripJsonc / deepMerge）+ `MarkdownConfigResolveTest`（主题解析 / 类型转换）；
- 🔧 **修复（2026-08-26 真机）**：① `copyAssetTree` 用 `AssetManager.list()` 判文件/目录——**Android 对文件返回空数组而非 null**，导致 `config-defaults/` 与 `appconfig/README.md` 被误建为目录 → 改为 `children.isNullOrEmpty()` 当文件 + 自愈（token 一致时也修复坏目录）；② 旧 JS 迁移生成的用户文件原本**没有逐项注释** → 新增 `formatMarkdownConfigJsonc()`（带 Section/字段注释的 JSONC 生成器），迁移时用「默认模板补齐缺失字段 + 保留用户改过的值」生成带注释的用户文件；
- ⏳ 真机验证待做：`.sync-token` 在同版本重装时是否可靠变化、旧 JS 迁移 + JSONC 热更链路。

### 11.5 恢复默认功能（已确认）

1. **作用范围**：只清文本配置（`appconfig/*.jsonc`），**保留资源文件**（`appconfig/fonts/`、`images/`、`audio/` 等）。
2. **实现方式**：不是删除文件，而是把 `config-defaults/` 对应默认模板**复制覆盖**到 `appconfig/` 同名文件（文件保持存在，内容变成当前默认，`version` 也同步更新）。
   - 注意：这样用户文件会变成「全量默认副本」，未来默认模板更新时该文件里的旧默认值会盖住新默认（与保守迁移一致）；想再吃新默认就再点一次恢复。
   - 备选：写空 `{}` 则未来更新自动生效；当前按「复制覆盖」确认。
3. **入口**：**外观设置**（不是聊天设置）里的危险选项，点击需**确认弹窗**。
4. **AI 命令**：§11 默认+覆盖机制落地后，AI 自然可以复制/覆盖文件实现恢复，不需要专门接口。
5. **版本与可修改项标注**：
   - 默认模板 JSONC 里标注「可修改项数量」和「版本号」；
   - 版本号用**时间戳**（如 `2026-08-27T14:06:52Z`），避免每次包更新都要手动 bump；
   - 更新检测**不做弹窗**：有更新时可由内置 agent 处理，且有默认值兜底；
   - 以后若做配置编辑 UI，再在 UI 里提示版本更新。
