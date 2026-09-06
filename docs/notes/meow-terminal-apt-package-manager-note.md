# 🐾 喵仓真终端「apt 包管理器」探讨笔记

> 日期：2026-08-22 · 主题：给内置真终端（PTY bash）加包管理器的可行路线
> 一句话总结：**Android 沙箱里没有系统级 apt；Termux 的 `pkg` 只是 `apt` 的壳；真要把 apt 搬进喵仓 runtime，最难的不是体积，而是硬编码路径 + SELinux linker64 包装。**

---

## 0. 背景：喵仓终端是什么

- 喵仓的终端不是模拟器，是 **真 PTY bash**：`terminal-host.js` 用 node-pty（Android fork）fork 持久 bash，跑在 App 私有目录的迷你 Termux 风格运行时里。
- runtime 位置：`filesDir/meow-runtime/`（如 `/data/data/com.meow.academy/files/meow-runtime`）。
- 现状 `PATH` 只有：`runtime/bin:/system/bin:/system/xbin`。
- 内置二进制来自 Termux：`bin/node`、`lib/bash.bin` + 一堆 `.so`，全部是**动态链接 Termux 库的 ELF**。
- `untrusted_app` 不能直接 exec 私有 ELF，所有二进制都要经 `/system/bin/linker64` 包装（node/bash 已是这个模式）。

---

## 1. 关键认知

### 1.1 Android 没有系统级 apt

- Android 不是 Debian/Ubuntu，系统里没有 dpkg/apt，也不存在“给系统装包”的路径。
- Termux 的 apt 能用，是因为它把包管理器装在**自己的 `$PREFIX`**（`/data/data/com.termux/files/usr`）里，不是系统包管理器。

### 1.2 `pkg` 是什么

- Termux 的 `pkg` **不是另一个包管理器**，它是 `apt` 的封装脚本（来自 `termux-tools` 包）。
- 关系链：

  ```
  pkg（Termux 方便壳）
   └─> apt（依赖解析 / 下载 / 安装）
        └─> dpkg（最底层，操作 .deb）
  ```

- 等价关系：

  ```bash
  pkg install python
  # ≈ apt update && apt install python

  pkg search python
  # ≈ apt search python

  pkg list-installed
  # ≈ apt list --installed
  ```

- 只拷 apt/dpkg 不会有 `pkg`，因为 `pkg` 是 `termux-tools` 包里的脚本。

### 1.3 ZeroTermux 136MB 为什么不算重

- 136MB 是 **APK + 基础引导**，不是完整环境。
- Termux 真实软件包落在 App 私有目录（`/data/data/com.zero.termux/files/usr`），`pkg install` 越装越大。
- proot 发行版 rootfs（Ubuntu/Debian 等）解压后可以到 **1~2GB**。
- 所以“136MB 不重”不矛盾；喵仓若要塞最小 Termux 前缀（apt + dpkg + termux-tools + keyring + bash），大概几十到一百多 MB，体积不是主要障碍。

---

## 2. 路径不一样是怎么回事（核心难点）

- Termux 软件包编译时前缀写死为：

  ```
  /data/data/com.termux/files/usr
  ```

- 喵仓 runtime 在：

  ```
  /data/data/com.meow.academy/files/meow-runtime
  ```

- 直接拷贝会出现三类“幽灵路径”：
  1. **脚本 shebang**：如 `#!/data/data/com.termux/files/usr/bin/sh`，目标路径不存在 → 脚本直接挂；
  2. **apt 内部默认目录**：编译期状态/缓存目录指向 Termux 前缀，需用 `apt.conf` 的 `Dir::State` / `Dir::Cache` 重新指路；
  3. **部分二进制内部硬编码**：工具自身把 `/data/data/com.termux/files/usr` 写死在逻辑里。

### 2.1 解决路径的三种办法

| 方案 | 说明 | 评价 |
|------|------|------|
| `termux-exec` | 官方包，`LD_PRELOAD` 注入 `libtermux-exec.so`，运行时把硬编码前缀改写成当前 `$PREFIX` | 最优雅，推荐 |
| proot 路径绑定 | 把喵仓目录 bind 成 `/data/data/com.termux/files/usr` 假路径 | 顺带解决，但引入 proot |
| 构建期 sed 批量替换 | 把所有 shebang/配置里的硬编码路径改成喵仓路径 | 脆弱，不推荐 |

---

## 3. 选定方案：真 Termux apt/dpkg 进 runtime.bin

- 扩展 `build-runtime.sh`，用 `copy_elf_with_deps` 拷 `apt`、`dpkg`、`apt-get` 及依赖 `.so`。
- 在 App 私有目录建 `$PREFIX`（如 `filesDir/usr`）+ `etc/apt/sources.list` + `var/lib/dpkg`。
- 关键坑：
  - 所有 ELF 都要 `linker64` wrapper；
  - 维护脚本 shebang 路径不对（靠 `termux-exec` 解决）；
  - 需要 `termux-keyring`、`termux-tools`、`termux-exec`；
  - `LD_LIBRARY_PATH` / `PREFIX` / `TMPDIR` 要配好；
  - runtime.bin 变大，升级要重打。
- 结论：可行但工程量大。

---

## 4. 结论

- 选定 **方案 A**（真 Termux apt/dpkg 进 runtime）：核心是 `termux-exec` 解决硬编码路径 + 全套 linker64 wrapper。
- 下一步按第 5 节做最小验证，跑通 `apt update` 后再决定集成进 `runtime.bin` 与 `DshProcessLauncher` 的 env。

## 5. 下一步候选实验（方案 A 最小验证）

1. 在 PC/真机 Termux 里把 `apt`、`dpkg`、`termux-tools`、`termux-keyring`、`termux-exec` 及依赖拷出；
2. 用 `build-runtime.sh` 同款 `copy_elf_with_deps` 打一个最小前缀；
3. 配 `apt.conf` 的 `Dir::State` / `Dir::Cache` / `Dir::Etc` 指向实际前缀；
4. `LD_PRELOAD=libtermux-exec.so` + `PREFIX` 环境变量，验证 `apt update` 是否跑通；
5. 跑通后决定是否集成进 `runtime.bin` 与 `DshProcessLauncher` 的 env。

## 6. 落地记录（2026-09-06 真机验收）

- **结论：主线方案 A 全部落地并真机通过**。`plan/plan-apt-package-manager.md` 阶段 2/3 完成，探针 `.tmp/apt-probe.mjs` 五步全 PASS。
- 最终执行链不是 termux-exec，而是自写 **`meow-exec.so`（LD_PRELOAD execve 转发）**：App 私有目录 ELF → `/system/bin/linker64`，脚本 → `/system/bin/sh`。
- 物理前缀 = `<filesDir>/data/data/com.termux/files/usr`，`dpkg --instdir=<filesDir>` 拼出真实可写路径，无需 proot。
- 真机踩坑与修复（按出现顺序）：
  1. `PATH` 必须含 `runtime/usr/bin`，否则 bash/session 里找不到 apt 本体；
  2. TLS/CA：`SSL_CERT_FILE` 不够，apt（libcurl）还要 `CURL_CA_BUNDLE` + apt.conf `Acquire::https::CAInfo`；
  3. apt 2.8.1 验签仍走 `apt-key`，必须把 Termux 的 `apt-key` 脚本拷进 runtime 并把硬编码 `/data/data/com.termux/files/usr/etc/apt/trusted.gpg.d` patch 成 `$MEOW_RUNTIME_DIR/...`；
  4. apt 给 dpkg 子进程设 `DPkg::Path`（Termux 硬编码），dpkg 因此在 App 域找不到 sh/rm/tar/diff/dpkg-split/start-stop-daemon → apt.conf 覆盖为 `$rt/usr/bin:/system/bin`；
  5. dpkg 解包需要 `tar`（GNU，toybox 不支持 `--warning=no-timestamp`）、`diff`、`start-stop-daemon`、`dpkg-split`、`dpkg-divert`，全部随 runtime 拷贝并带依赖（如 libacl.so）；
  6. RuntimeExtractor 解压后会把非 node/bash 的可执行位丢光，需对 `usr/bin/*`、`usr/lib/apt/methods/*`、`meow-exec.so` 补 `setExecutable`；
  7. 装出的 `file` 硬编码 Termux magic 路径 → launcher 注入 `MAGIC=$PREFIX/share/misc/magic` 指向物理前缀内真实文件。
- 遗留：版本号仍 0.2.9/versionCode 11，0.2.10/versionCode 12 待主人拍板；`RELEASE_NOTES_0.2.10.md` 与 AGENTS.md 版本状态行待正式发行时补。

---

*关联：`android-app/runtime-assets/build-runtime.sh`、`android-app/runtime-assets/meow-exec.c`、`DshProcessLauncher.kt`、`RuntimeExtractor.kt`、`terminal-host.js`*
