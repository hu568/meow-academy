# 版本更新记录

> `release/` 只存放安装包与本更新记录，其他文件（截图、调试脚本）一律放 `.tmp/`。

## [0.2.0] - 2026-08-21（debug）

第二个 debug 包（约 87MB，内置 DSH 运行时）。0.1.0 之后的体验打磨 + M4 文件管理落地。

**新特性：**

- ✨ **M4 文件管理（数据中心）**：浏览 / 编辑 / 搜索 / 导入 / 复制 / 移动 / 多选 / 排序，面包屑可编辑 + 目录切换快捷栏，与终端 `cd` 双向联动；INTERNAL 根语义改为「工作区」（filesDir/workspace），不再暴露系统目录
- ✨ **Markdown 渲染 JS 化**：`appconfig/markdown-config.js` 驱动渲染外观（Rhino 求值 + FileObserver 热更），公式块圆角背景、列表 `·` 大小描边、代码块整块圆角、引用/链接/标题倍率/分割线全可配——**AI（DSH）用 write 工具改 JS 即实时生效**（真机验证通过）
- ✨ **Markdown 流式渲染重做**：块级增量渲染 + LaTeX 公式 + Prism4j 代码着色 + 表格走 Compose 定宽通道（消除流式跳动）
- 🎨 **聊天页美化**：半透明毛玻璃会话抽屉、大号底部导航 + 键盘动画、可更换聊天底图（预设/相册/自定义）、自定义主题种子色
- 🎨 **喵学堂更名「喵仓」**：应用名 / 通知 / 图标全面换新（全密度 PNG 启动图标）

**改进：**

- 🔧 phase4 目录重构：workspace / appconfig / .agents 三保险就绪；DSH 工作区收敛到 workspace（DSH_CWD/HOME/bash cwd 同步）；settings 迁 `appconfig/dsh-settings.json`，credentials 路径随迁；persona 更新（工作区路径 + 敏感文件禁读提示）
- 💬 聊天页脱离自动滚动：上滑脱离 / 回底恢复 + 「回到底部」按钮；看历史时冻结流式气泡不再跳动

**修复：**

- 🔧 浅色模式聊天底图暗纱遮罩 + Markdown 文字跟随主题 + 底图内存缓存
- 🔧 工具栏不抢焦点；代码块圆角「每行一个」改为整块一个圆角

安装：`adb install -r release/meow-academy-0.2.0-debug.apk`
