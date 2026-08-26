# 🐾 喵仓文件编辑/预览「跨 tab 状态保持」笔记

> 日期：2026-08-26 · 主题：文件管理页的编辑/预览状态在底部导航切换、系统明暗色切换后原地恢复
> 一句话总结：**把「正在编辑/预览哪个文件」从 FilesScreen 内部提升到 MainScreen 顶层 rememberSaveable 持有（只存 path），再用 SaveableStateHolder 包住 FILES tab 内容，让编辑器内部 13 个状态（mode/fieldValue/scroll/undo…）跨 tab 保存恢复；HTML 预览的 WebView 因为切走会销毁重建、onPageFinished 时 contentHeight 常为 0，改为轮询重试恢复滚动。**

---

## 0. 背景：问题从哪来

- 喵仓文件管理（M4）点文本/HTML 文件会全屏打开统一编辑器 FileEditorScreen（文本默认编辑、HTML 默认预览）。
- 底部导航（MainScreen）用 when(selectedTab) 切 聊天 / 文件 / 设置 三页。
- 原本「正在编辑的文件」状态存在 FilesScreen 内部的 remember { mutableStateOf<FileEntry?>(null) } —— 一切 tab 就销毁，回来就丢。

### 三个递进的问题（对应三轮修复）

1. 切 tab 直接退到文件管理列表：editingFile 在 FilesScreen 内 remember，切走销毁、回来重置为 null。
2. 切回后编辑跳到第一行 / 预览变成编辑：mode、fieldValue、undoStack、滚动位置全是 remember，随 composition 销毁归零。
3. HTML 预览切回跳到第一行：即使 mode/fieldValue 都恢复了，HTML 的 WebView 在切走时被销毁、回来重建重载，onPageFinished 时 contentHeight 往往还是 0 → 恢复滚动被跳过。

---

## 1. 修复一：editingPath 提升到 MainScreen 顶层（只存 path）

### 思路

- 「正在编辑/预览哪个文件」是跨 tab 要保命的状态 → 放 MainScreen 顶层，且用 rememberSaveable（String 原生可存）。
- FileEntry 不是 Parcelable，不能直接 rememberSaveable → 只存 path，渲染时用新增的 FileRepository.toFileEntry(File) 按 path 重建条目（文件不存在返回 null → 自动退出编辑/预览）。

### 结构变化

- FilesScreen 去掉内部的 editingFile/previewFile 和早退逻辑，改为三个回调参数：onOpenFile(FileEntry) / onOpenImage(FileEntry) / onOpenTerminal(String)。
- MainScreen 新增 FileTabContent 私有 Composable：三态互斥（列表 / 编辑 / 图片预览），渲染在 FILES tab 内容区，不再叠加到其他 tab（之前放 Scaffold 外会叠在聊天页上）。

### 关键坑

- remember { FileRepository(LocalContext.current.applicationContext) } 编译报「@Composable invocations…」——remember 的 calculation 是 @DisallowComposableCalls，必须先 val appContext = LocalContext.current.applicationContext 取到顶层再传进去。
- FileRepository 直接用 LocalContext.current.applicationContext 构造即可，不需要强转 as Application。
- 顶层 filesViewModel: FilesViewModel = viewModel() 与 FilesScreen 内部的是同一个实例（共享 LocalViewModelStoreOwner），保存/删除后调 refresh() 列表能更新。

---

## 2. 修复二：FileEditorScreen 内部 13 个状态改 rememberSaveable + SaveableStateHolder

### 思路

editingPath 留在顶层只是「知道在编辑哪个文件」，编辑器内部的状态（mode/内容/滚动/撤销栈）还随 composition 销毁。两个关键手段配合：

1. MainScreen 用 rememberSaveableStateHolder()（放 when(selectedTab) 之外的 Box 顶层），FILES 分支用 saveableStateHolder.SaveableStateProvider("files") { FileTabContent(...) } 包住。切走 tab 时 provider 退出 composition 自动保存内部所有 rememberSaveable，切回恢复。holder 自身也是 rememberSaveable → 系统切换明暗色（Activity 重建）也一并恢复。
2. FileEditorScreen 的 13 个状态从 remember 改 rememberSaveable：

| 状态 | 类型 | Saver |
|------|------|-------|
| loadedPath | String? | 原生 |
| fieldValue | TextFieldValue | TextFieldValue.Saver |
| isLoading | Boolean | 原生 |
| previewError | String? | 原生 |
| editBlocked | String? | 原生 |
| htmlContentLoaded | Boolean | 原生 |
| mode | EditorMode | 自定义 EditorModeSaver（存 name） |
| editScroll / previewScroll | ScrollState | ScrollState.Saver |
| htmlScrollFraction / anchorFraction | Float | 原生 |
| undoStack / redoStack | List<TextFieldValue> | 自定义 TextFieldValueListSaver |

### 两个自定义 Saver

- EditorModeSaver = Saver<EditorMode, String>(save = { it.name }, restore = { runCatching { EditorMode.valueOf(it) }.getOrNull() }) —— enum 默认不能存 Bundle。
- TextFieldValueListSaver = listSaver<TextFieldValue, Any>(...) —— listSaver 的 save 参数是「单个 Original → List<Saveable>」，不是 List → List，别写反；内部用 with(TextFieldValue.Saver) { list.map { save(it)!! } } 才能让 Saver 接口的 save(SaverScope, Original) 正确解析（直接 saver.save(scope, it) 在这个 lambda 里编译不过）。

### 保持 remember 不改（瞬态）

- currentFile（父级 file 参数 + LaunchedEffect(file.path) 同步）
- suppressCursorFollow / pendingRestore（模式切换瞬态）
- textLayout / viewportHeightPx / fieldOffsetY（布局瞬态，下帧重新填充）
- 三个对话框 flag

### 关键坑

- 关闭编辑器（onBack/onSaved/onDeleted）时调 saveableStateHolder.removeState("files") 清缓存，否则下次打开新文件会恢复到旧文件的状态。
- 重命名不能加 key(file.path)：加了会整体销毁重建丢编辑态。FileEditorScreen 内部已有 LaunchedEffect(file.path) 同步 currentFile，重命名保留编辑态。
- 切到聊天/文档时编辑器会整体退出 composition（FileTabContent 受 selectedTab 约束），不会叠在其他页上（第一版放 Scaffold 外层会叠，已修）。

---

## 3. 修复三：HTML 预览滚动轮询重试（HtmlWebView）

### 为什么轮询而不是常驻

- HTML 预览的 WebView 在切走 tab 时被 DisposableEffect 调 webView.destroy()，切回时重建、自动重新加载 HTML。
- onPageFinished 触发时页面布局往往还没完成，contentHeight 是 0 → 原代码 if (contentHeight > 0) 失败 → scrollTo 被跳过 → 停顶；之后布局完成但再没有恢复逻辑触发。
- 曾考虑「WebView 常驻后台」：WebView 强绑 Activity Context（重建必泄漏）、Compose 组合模型不天然支持「常驻隐藏」（隐藏内容仍参与命中测试/BackHandler 拦截）、WebView 单实例几十 MB 内存——权衡下选轮询重试（改动局部、风险低）。

### 修复

HtmlPreview.kt 的 onPageFinished 改成轮询：

- 每 100ms 查一次 contentHeight，最多 30 次（3s），>0 就 scrollTo(0, fraction * contentHeight)。
- fraction 来自 htmlScrollFraction（rememberSaveable 已跨 tab 保存），HtmlWebView 的 initialScrollFraction 参数改为直接传 htmlScrollFraction（不再用 anchorFraction）。
- runCatching 兜底：重试期间用户又切走导致 WebView 销毁时 contentHeight 访问返回 0，attempts 耗尽自然停止，不崩溃。
- 常量：SCROLL_RESTORE_MAX_ATTEMPTS = 30、SCROLL_RESTORE_RETRY_MS = 100L。
- 坑：WebView.isDestroyed() 在 android-34 stub jar 里编译不过（Unresolved reference），别用，靠 runCatching + attempts 上限兜底。

---

## 4. 行为对照表

| 场景 | 修复前 | 修复后 |
|------|--------|--------|
| 编辑中切到聊天 → 切回 | 退到列表 | 原地回到编辑，模式/内容/光标/滚动/撤销栈全保留 |
| Markdown 预览切到聊天 → 切回 | 变成编辑 | 保持预览 + 滚动位置 |
| HTML 预览切到聊天 → 切回 | 跳到第一行 | 保持预览 + 阅读位置（轮询等布局就绪后滚回） |
| 系统切换明暗色 | 退到列表/跳顶 | 编辑/预览状态走 Bundle 恢复 |
| 保存/删除/重命名 | — | 正确关闭编辑器 / 原地更新，removeState 清缓存 |
| 文件被外部删除 | — | toFileEntry 返回 null → 自动退出浮层回列表 |

---

## 5. 改动文件清单

- android-app/app/src/main/java/com/meow/academy/ui/MainScreen.kt — editingPath/previewPath 顶层 rememberSaveable + SaveableStateHolder + FileTabContent
- android-app/app/src/main/java/com/meow/academy/ui/files/FilesScreen.kt — 移除本地编辑器状态，改 onOpenFile/onOpenImage 回调
- android-app/app/src/main/java/com/meow/academy/ui/files/FileEditorScreen.kt — 13 状态改 rememberSaveable + 两个自定义 Saver + HTML initialScrollFraction 改用 htmlScrollFraction
- android-app/app/src/main/java/com/meow/academy/data/files/FileRepository.kt — 新增 toFileEntry(File) 助手
- android-app/app/src/main/java/com/meow/academy/ui/files/HtmlPreview.kt — onPageFinished 轮询重试恢复滚动

