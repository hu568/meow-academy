# 版本更新记录

## [0.2.8] - 2026-08-31（debug）

第十个 debug 包（约 95MB）。核心变更：**全面屎山拆解——把 7 个巨型界面文件拆成职责分片薄壳**（0.2.7 的四模式功能全部保留，纯代码质量重构，外部零行为改动）+ 3 项聊天体验修复。

**代码质量重构（屎山拆解，外部零改动）：**

- 🔧 `ChatViewModel` 1308→182 行：拆成 5 职责控制器 + 1 `EventRouter`
- 🔧 `FileEditorScreen` 1189→430 行：状态机 + 编辑/预览分片 + 组件化
- 🔧 `ChatScreen` 631→300 行：薄壳 + 三分片（消息列表/顶栏/看板 overlay）+ 附件纯变换沉淀
- 🔧 `FilesScreen` 605→422 行：薄壳 + 状态收敛×5 组 + `FileListComponents` 四拆（KindUtils/Dialogs/Breadcrumb）+ 长按菜单外迁 `FileEntryMenuDialog`
- 🔧 `SessionDrawer` 593→122 行：薄壳 + 4 职责分片（Toolbar/List/Dialogs/Row）+ 去 viewModel 隐式耦合
- 🔧 `ChatInputBar` 596→135 行：薄壳 + 4 职责分片（附件预览/工具栏/胶囊/ModeDialogs）
- 🔧 `DashboardDrawer` 512→267 行：薄壳 + 3 职责分片 + 1 纯几何文件（DashboardDrawerGeometry/TabRail/PanelContent）

**本包修复（聊天体验）：**

- 🐛 聊天：无语言标签代码块的复制按钮靠右——加 weight 占位 Spacer，与带语言标签的代码块对齐
- ✨ 聊天：长 Markdown 宽表下半部分滑动手势穿透到抽屉——`MarkdownCellTextView` 不吞非链接按下 + `TableScrollRegistry` 外层兜底直接驱动横向滚动
- 🐛 聊天：修行内代码圆角背景漂移/圆角错位——根因不是圆角判断写错，是坐标算错

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.7...v0.2.8
