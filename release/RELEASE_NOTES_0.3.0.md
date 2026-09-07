# 版本更新记录

## [0.3.0] - 2026-09-07（debug）

第十二个 debug 包（约 101MB，干净构建；0.2.9 包因增量打包膨胀至 179M，本包已恢复正常体积）。核心变更：**内置 apt 包管理器**——真 Termux apt/dpkg 进 runtime，`apt-get update / install / 执行安装包` 全链路真机 PASS，另有两波 apt 体验优化；辅以聊天文案适配自定义角色、文件管理修复与一轮内部代码质量治理。

**新特性：**

- 📦 **内置 apt 包管理器**：真 Termux apt/dpkg 进 runtime——`meow-exec.so` 以 `LD_PRELOAD` 做 execve 转发（私有 ELF→linker64、脚本→/system/bin/sh），物理前缀复刻 `<filesDir>/data/data/com.termux/files/usr` + `dpkg --instdir`；runtime 内置 apt-key/gpgv/tar/diff/start-stop-daemon/dpkg-split/dpkg-divert 全家依赖，`apt-get update / install file / file 执行` 全链路真机 PASS
  - 关键坑链：PATH 加 `runtime/usr/bin`、TLS CA 走 `CURL_CA_BUNDLE` + `Acquire::https::CAInfo`、apt.conf 覆盖 `DPkg::Path`、`MAGIC` 指向物理前缀等，详见 `plan/plan-apt-package-manager.md` 与 `docs/notes/meow-terminal-apt-package-manager-note.md` §6
- ✨ **apt 优化第一波**：① 默认镜像切官方源 `packages.termux.dev`（第三方镜像索引过期 404）；② `sources.list` 只在缺失时播种、不再每次重写（用户/AI 运行时换源不被启动覆盖）；③ git 编译期路径 env 接管：`GIT_TEMPLATE_DIR`/`GIT_EXEC_PATH` + `GIT_CONFIG_NOSYSTEM=1`/`GIT_ATTR_NOSYSTEM=1`（跳过 App 域 EACCES 的系统级 git 配置，init/commit 全链实测无 warning）；④ 播种 `$PREFIX/bin/{sh,bash}` symlink → `lib/bash.bin`（dpkg 编译期硬编码 shell 路径保险，幂等）；⑤ runtime 内置 `apt-fix` 一键修复脚本（`dpkg --configure -a` + `apt-get -f install` + 清 meow-rewrite 残留）
- ✨ **apt 优化第二波**：`meow-exec` 加 **dpkg 维护脚本内容重写**——exec 转发目标是 `dpkg/info/*.{preinst,postinst,prerm,postrm,config}` 且 shebang 时，把硬编码 Termux 前缀词边界替换为 `$PREFIX` + 头插自删 trap 后再转发；失败静默回退原路径。真机效果：**openssh 的 `ssh_host_{rsa,ecdsa,ed25519}_key` ×3 首次真实生成**（此前全程 `Saving key failed`）
- 💬 **聊天页文案去角色化**：4 处「喵喵老师」硬编码改通用表述（输入栏 placeholder/空会话标题/问答卡自定义输入提示/附加目标弹窗），适配 0.2.9 引入的自定义角色功能

**修复：**

- 🐛 **文件管理页 Snackbar 4 秒自动关闭兜底**——修复删除文件后提示气泡不消失

**代码质量（内部重构，行为不变）：**

- 🔧 FileRepository 吞错治理 + 职责拆分：28 处 `runCatching` 全部可追溯（错误留痕）+ 纯判断工具外迁 `FileTypes` + 修复分享打包 Kotlin 字符串模板回归
- 🔧 工作设置面板拆分：`WorkspaceSettingsPanel` 656 行 → 薄壳适配器 + 三分片纯参数注入（`PresetCard`/`WorkspaceSection`）+ 五态卡独立成文件（ChatScreen 零改动）
- 🔧 meow-jsonrpc 整改①②：吞异常留痕（不再静默吞错）+ 魔法值收编 + 纯函数外迁三件套（`errors.js`/`log.js`/`model.js`/`stats.js`）

**已知限制：**

- `apt-get install --reinstall` 的 dpkg fork postinst 执行器子进程在 execvp hook 进入前被 seccomp SIGSYS 杀（fork 快照实锤 env 完整、黑盒未明）——**postinst 主体实际执行完成**，仅 dpkg 状态记成 iF；跑 `apt-fix` 一键修复回 ii
- 排障基建（默认零开销）：`MEOW_EXEC_DEBUG=2` 写 `$PREFIX/tmp/meow-exec-debug.log` + meow-exec hook fork 记父子 env 快照

**Full Changelog**: https://github.com/hu568/meow-academy/compare/v0.2.9...v0.3.0
