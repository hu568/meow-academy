# 版本更新记录

## [0.2.1] - 2026-08-21（debug）

第三个 debug 包（约 80MB）。核心变更：**内置 DSH 运行时从 rc.5 升级到 `dsh-v0.1.1-rc.1`**（819 commits / 54 feats，上游 2026-08-21 tag）。fork 全部源码改动已固化为 patch 入库（`android-app/runtime-assets/dsh-fork/`，基线 `528c682e06`）。

**新特性（随 DSH 升级获得）：**

- ✨ **DeepSeek 多模态视觉模型**：`deepseek-v4-flash-vision-exp` 已出现在模型列表——聊天发图的基础能力就位
- ✨ **low reasoning effort 思考档位**：模型管理思考强度新增低档位
- ✨ **session SQLite 持久化布局优化**：schema 15 → 17，会话恢复更稳
- ✨ **subagent ×7 / credentials / authorization 强化**等上游 54 个功能改进

**本包修复：**

- 🔧 **subprocess-local koffi 原生绑定惰性加载**：新版 Windows 进程检查器在模块顶层触达 koffi 原生绑定，而 koffi 无 Android bionic arm64 prebuilt——静态 import 会让 DSH 插件树在 Android 上加载即崩（PC 冒烟测不出，真机才暴露）；改为首次调用惰性解析，Linux `/proc` 检查器不受影响
- 🔧 **node-prune 误删 workspace yaml 的自愈**：WSL 下闭包构建的裁剪步骤会波及 `yaml@2.9.0/dist/doc/`，构建脚本已内置反向补回
- 🔧 **fs-local 探测式 rename**：guarded-create 发布原语改为「lstat 探测 → EEXIST 上报 → rename」，保住上游 no-replace 竞争语义（SELinux 禁 link()）

**⚠️ 升级须知：**

- 旧版 DSH 会话库（schema 15）不迁移，**旧会话不可续聊**（界面聊天记录 Room 库不受影响）；升级后若遇 prompt 被拒，清除 `files/.dsh-sessions/` 重启即可（新库自动重建）
- 真机验收：initialize 握手 ✓ 模型列表 ✓ 聊天流式 / 工具六件套 / resume 请按需复测

安装：`adb install -r release/meow-academy-0.2.1-debug.apk`

**Full Changelog**: https://github.com/hu568/meow-academy/compare/meow-academy-0.2.0-debug...v0.2.1
