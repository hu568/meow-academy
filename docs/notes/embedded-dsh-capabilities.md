# 内置 DSH 能力探讨笔记（嵌入式 DSH 自举 / 插件化 / 模式创作）

> 本文档是 2026-08 讨论记录：喵仓 App 内置的 DSH 后端，
> 与 PC 上完整版 DSH 在「插件化 / 自打包 runtime / 自创模式」三个维度上的能力边界。

## 1. 内置 DSH 是怎么跑的（现状）

- App 是正常的安卓 APK（Kotlin + Compose），但 **DSH 后端不是编译进安卓程序的原生代码**。
- 它以 **Node.js 软件包**的形式运行在 **App 内置的 Termux runtime** 里：
  - `android-app/app/src/main/assets/runtime.bin`（gzip 流）= Termux node + 动态库 + CA 证书 + DSH 闭包（node_modules）+ bash + 真终端宿主。
  - `DshProcessLauncher.kt` 用 `/system/bin/linker64` 拉起 `node bin/terminal-host.js`（untrusted_app 不能直接 exec 私有 ELF）。
  - `terminal-host.js` 用 node-pty Android fork 起**真 PTY bash**，DSH 作为 bash 后台子进程运行：
    `linker64 node …/dsh-sdk-jsonrpc-demo/lib/packaged-bin.js dsh/cordis.yml &`
  - 聊天走本地 unix socket JSON-RPC（`DSH_JSONRPC_SOCKET`，meow-jsonrpc 插件），终端字节走 `DSH_TERMINAL_SOCKET`；stdio 已被 PTY 占用。
- 结论：**DSH 后端 = 跑在内置 Termux Linux 环境真终端里的 Node 软件包**，不是安卓原生程序，也不是纯模拟终端。

## 2. 插件化能力（结论：与真 DSH 同架构，但更新靠重打包）

- 内置 DSH 保留完整 Cordis 插件机制（Loader / include / group / service / 事件）。
- 增删插件 = 改 `cordis.yml` 条目 + 闭包依赖，不动核心：
  - 加官方插件：闭包清单装包 + `cordis.yml` 加一行 `- name: '@deepseek-ai/dsh-xxx'`；
  - 删插件：移除条目（可再移出依赖清单省体积）；
  - 自写插件：写 `apply(ctx, config)` 放 `runtime-assets/dsh/meow-extensions/`，相对路径引用（已实现 `meow-jsonrpc.js`，实测过 hello 插件增删验证）。
- 限制：
  - 不是 App 运行时热改：官方插件要 PC 重打闭包（`build-dsh-closure.sh`）→ 真机重打 `runtime.bin`（`build-runtime.sh`）→ 回拷 assets → 重建 APK。
  - 安卓兼容性限制：sandbox 全家桶（landlock 原生模块）会崩，无法打包；node-pty 需 Android fork 版。
- 自制 JS 插件理论上可完全在设备端完成（终端写文件 + 改 cordis.yml + 重启 DSH 进程），只差一个 App 内「开发者模式」入口。

## 3. 用内置终端自打包 runtime.bin（结论：可行，但需要自举版脚本）

- 当前 `build-runtime.sh` 是为「外部 Termux」写的，直接在内置终端跑会失败：
  依赖 greadelf/readelf（binutils）、npm、`$PREFIX` 环境、GNU tar/gzip，且文件难以进出 App 私有目录。
- 可行变体「自举打包」：内置 runtime 自带全部原料（node、lib/*.so、node-pty fork），可跳过 readelf 与 npm：
  1. PC：`build-dsh-closure.sh` 生成闭包 tgz（这步离不开 PC：pnpm + DSH checkout 安卓上没有）；
  2. 闭包 tgz 通过 SAF / adb `run-as` 导入 App；
  3. 设备端跑 `build-runtime-inplace.js`（node zlib 直接生成 tar.gz）：拷贝当前 runtime 的 node/lib/pty + 解包闭包 + 物化 symlink → runtime.bin；
  4. SAF 导出 / adb pull 回仓库 assets。
- 建议：做成 App 开发者工具（前台服务/WorkManager），不要在 PTY 终端裸奔（App 被杀会断）。

## 4. 自己给自己做「模式」（结论：创作链没打包，但记忆可自理）

- PC 上的「记忆模式」是主人用「创造模式」（=`cordis` agent preset）创作的：依赖
  `dsh-agent-presets`（复制/挂载/校验）+ Web UI + 会话内检查工具，注入 SOUL.md / USER.md / MEMORY.md。
- 喵仓闭包清单 **没有 `dsh-agent-presets`**，Web UI 全家桶未打包；
  `cordis.yml` 中 agent-spine-demo `skills: enabled: false`，也未挂 `agent-instructions` 行。
- 因此内置 DSH 是「能跑 Agent」，不是「能造 Agent 的 Agent」——不能复刻创造模式的创作-挂载流程。
- 但它同一引擎自带 `dsh-agent-instructions`、文件工具、bash、技能加载器、SQLite 会话记忆，
  **能自己读 / 自己写记忆文件、提示词、技能文件**，改完重启进程生效。
- 要完整支持「自创模式」，需两步：
  1. PC 上把 `dsh-agent-presets` 加入 `deploy/meow-runtime` 闭包清单（一次打包）；
  2. App 增加「重启 DSH / 切换模式」入口。

## 5. 待办 / 可选方向

- [ ] （可选）App 内开发者模式：终端写文件 → 重启 DSH，支持设备端自写插件。
- [ ] （可选）`build-runtime-inplace.js` 自举打包 + SAF 导入导出。
- [ ] （可选）闭包加入 `dsh-agent-presets` + App 模式切换入口，实现「内置 DSH 自创模式」。

## 参考文件

- `android-app/app/src/main/java/com/meow/academy/runtime/DshProcessLauncher.kt`
- `android-app/runtime-assets/terminal-host.js`
- `android-app/runtime-assets/build-runtime.sh`
- `android-app/runtime-assets/build-dsh-closure.sh`
- `android-app/runtime-assets/dsh/cordis.yml`
- `android-app/runtime-assets/dsh/meow-extensions/meow-jsonrpc.js`
- `dsh/deploy/meow-runtime/package.json`（闭包清单，缺 dsh-agent-presets）
- `docs/decision-dsh-agent.md`（插件化机制 §四）