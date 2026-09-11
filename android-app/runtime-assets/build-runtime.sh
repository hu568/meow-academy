#!/usr/bin/env bash
# ============================================================
# build-runtime.sh —— 打包喵仓运行时（DSH 版，阶段 1）
#
# 在真机 Termux 上运行（需 nodejs-lts + binutils + bash）：
#   1. 拷贝 node + bash 二进制及其 Termux 动态库（cp -L 解引用 symlink）
#   1.5 内置 apt 包管理器：apt/dpkg/gpgv/methods/keyring + 编译 meow-exec.so（0.2.10）
#   2. 解包 PC 端 build-dsh-closure.sh 生成的 DSH 闭包（node_modules + dsh/）
#   3. 真终端 node-pty + CA 束 + DNS shim（旧坑原样保留）
#   4. tar czf 打包为 meow-runtime/ 顶层目录的 gzip 流 → runtime.bin
#
# 产物拷回 PC 后放到 app/src/main/assets/runtime.bin（gitignore 已排除）。
# RuntimeExtractor 解压时剥离 meow-runtime/ 前缀。
#
# 用法： bash build-runtime.sh <dsh-closure.tar.gz> [输出目录=脚本同级]
#   输出： <输出目录>/runtime.bin
# ============================================================
set -euo pipefail

CLOSURE_TGZ="${1:?用法: bash build-runtime.sh <dsh-closure.tar.gz> [输出目录]}"
OUT_DIR="${2:-$(cd "$(dirname "$0")" && pwd)}"
OUT_FILE="$OUT_DIR/runtime.bin"

# ── 0. 环境检查 ──
command -v node >/dev/null || { echo "✗ 未找到 node，请先 pkg install nodejs-lts" >&2; exit 1; }
command -v bash >/dev/null || { echo "✗ 未找到 bash，请先 pkg install bash" >&2; exit 1; }
command -v gzip >/dev/null || { echo "✗ 未找到 gzip" >&2; exit 1; }
[ -f "$CLOSURE_TGZ" ] || { echo "✗ 找不到闭包：$CLOSURE_TGZ" >&2; exit 1; }

NODE_BIN="$(command -v node)"
BASH_BIN="$(command -v bash)"

echo "» node    : $NODE_BIN"
echo "» bash    : $BASH_BIN"
echo "» 闭包    : $CLOSURE_TGZ"
echo "» 输出    : $OUT_FILE"

# ── 1. 暂存目录布局（与 App 端 RuntimeExtractor 期望一致） ──
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
RUNTIME="$STAGE/meow-runtime"
mkdir -p "$RUNTIME/bin" "$RUNTIME/lib"

# ── 2. ELF 二进制 + 依赖动态库（cp -L 解引用 Termux 的 symlink）──
# Termux 没有 ldd，用 binutils 的 greadelf 枚举 NEEDED；迭代补齐传递依赖；
# 系统库（libc/libm/libdl）不在 $PREFIX/lib 下，自动跳过，由 linker64 运行时解析。
READELF="$(command -v greadelf || command -v readelf || true)"
[ -n "$READELF" ] || { echo "✗ 缺少 readelf/greadelf，请 pkg install binutils" >&2; exit 1; }

copy_elf_with_deps() {
  local src="$1" dst="$2" need_file="$STAGE/need-$3.txt"
  echo "» 拷贝 $3…"
  cp -L "$src" "$dst"
  # readelf -d 只对 ELF 有意义；脚本（如 dpkg-maintscript-helper）直接拷入即可，
  # 不需要（也没法）枚举 NEEDED。set -e 下必须用 if 接住失败，否则脚本文件会中断打包。
  if "$READELF" -d "$dst" >/dev/null 2>&1; then
    "$READELF" -d "$dst" | sed -n 's/.*NEEDED.*\[\([^]]*\)\].*/\1/p' > "$need_file"
  else
    : > "$need_file"
  fi
  while :; do
    local added=0
    while read -r lib; do
      [ -n "$lib" ] || continue
      [ -f "$RUNTIME/lib/$lib" ] && continue
      if [ -f "$PREFIX/lib/$lib" ]; then
        cp -L "$PREFIX/lib/$lib" "$RUNTIME/lib/"
        "$READELF" -d "$PREFIX/lib/$lib" 2>/dev/null | \
          sed -n 's/.*NEEDED.*\[\([^]]*\)\].*/\1/p' >> "$need_file"
        added=1
      fi
    done < "$need_file"
    [ "$added" -eq 0 ] && break
  done
}

# node / bash 真 ELF 都放 lib/：untrusted_app + targetSdk ≥ 29 的 W^X 限制下，App 域 exec app
# 数据文件一律 EACCES（私有 ELF 与 shebang wrapper 脚本一视同仁，实测 bad interpreter: Permission
# denied），须由 linker64 加载。bin/ 下 wrapper 仅供 run-as/Termux 手测域使用（App 域内 PATH 命中
# node/bash 的实际通道是 launcher 注入的 BASH_FUNC_* 导出函数，见 DshProcessLauncher.kt）；
# wrapper 用 MEOW_RUNTIME_DIR 定位（launcher 注入的 runtime 绝对路径，分身用户路径也能对上），
# 保留 $HOME/meow-runtime fallback 供 Termux 侧手工测试。
copy_elf_with_deps "$NODE_BIN" "$RUNTIME/lib/node.bin" node
copy_elf_with_deps "$BASH_BIN" "$RUNTIME/lib/bash.bin" bash
cat > "$RUNTIME/bin/bash" <<'EOF'
#!/system/bin/sh
exec /system/bin/linker64 "${MEOW_RUNTIME_DIR:-$HOME/meow-runtime}/lib/bash.bin" --norc --noprofile "$@"
EOF
cat > "$RUNTIME/bin/node" <<'EOF'
#!/system/bin/sh
exec /system/bin/linker64 "${MEOW_RUNTIME_DIR:-$HOME/meow-runtime}/lib/node.bin" "$@"
EOF
chmod +x "$RUNTIME/bin/bash" "$RUNTIME/bin/node"
echo "  lib/ 现有 $(ls "$RUNTIME/lib" | grep -c '\.so' || true) 个 .so"

# ── 2.5 内置 apt 包管理器（0.2.10，plan-apt-package-manager.md §4.5/阶段3）──
# Termux 的 apt/dpkg/gpgv 家族搬进 runtime 只读区（usr/bin/），下载 methods 进
# usr/lib/apt/methods/，keyring 进 usr/etc/apt/trusted.gpg.d/；依赖 .so 统一并入
# lib/（LD_LIBRARY_PATH 兜底，copy_elf_with_deps 自动去重）。装出的包走 filesDir
# 可变区（<filesDir>/data/data/com.termux/files/usr），由 DshProcessLauncher
# .ensureAptEnv() 启动期幂等播种 apt.conf / sources.list / dpkg 数据库。
echo "» 拷贝 apt/dpkg/gpgv 家族（apt 包管理器）…"
mkdir -p "$RUNTIME/usr/bin" "$RUNTIME/usr/lib/apt/methods" "$RUNTIME/usr/etc/apt/trusted.gpg.d"
for b in apt apt-get apt-cache apt-config apt-mark dpkg dpkg-query dpkg-deb dpkg-trigger \
         dpkg-split dpkg-divert update-alternatives gpgv dpkg-maintscript-helper \
         tar diff start-stop-daemon; do
  if [ -x "$PREFIX/bin/$b" ]; then
    copy_elf_with_deps "$PREFIX/bin/$b" "$RUNTIME/usr/bin/$b" "apt-$b"
  else
    echo "  ⚠ 缺 $PREFIX/bin/$b，跳过（不影响主链）" >&2
  fi
done
# apt-key 是 POSIX sh 脚本（apt 更新源时仍会调它做 InRelease 验签），
# 不能走 copy_elf_with_deps（readelf 对它无意义）；拷入后把脚本里硬编码的
# Termux 前缀路径替换成 MEOW_RUNTIME_DIR 运行时定位（App 域读不到真实 Termux 路径）。
if [ -f "$PREFIX/bin/apt-key" ]; then
  cp -L "$PREFIX/bin/apt-key" "$RUNTIME/usr/bin/apt-key"
  sed -i \
    -e 's#/data/data/com.termux/files/usr/etc/apt/trusted.gpg.d#$MEOW_RUNTIME_DIR/usr/etc/apt/trusted.gpg.d#g' \
    -e 's#/data/data/com.termux/files/usr/etc/apt/trusted.gpg#$MEOW_RUNTIME_DIR/usr/etc/apt/trusted.gpg#g' \
    "$RUNTIME/usr/bin/apt-key"
  chmod +x "$RUNTIME/usr/bin/apt-key"
  echo "  + apt-key（已 patch 路径）"
else
  echo "  ⚠ 缺 $PREFIX/bin/apt-key（apt update 验签会失败）" >&2
fi
# apt-fix 一键修复脚本（0.2.10 优化，plan-apt-optimization.md §3.5）：dpkg --configure -a + apt-get -f install
# 修复安装中断的 broken state。物理前缀经 $PREFIX 定位（launcher 注入），默认 fallback 兼容手测。
cat > "$RUNTIME/usr/bin/apt-fix" <<'EOF'
#!/system/bin/sh
# apt-fix —— 修复 dpkg 中断状态（喵仓专用：物理前缀 + 硬编码 admindir）
P="${PREFIX:-/data/user/0/com.meow.academy/files/data/data/com.termux/files/usr}"
# meow-exec 维护脚本重写残留兜底（脚本自带 trap 被覆盖时可能残留）
rm -f "$P/tmp"/meow-rewrite-*.sh 2>/dev/null
dpkg --admindir="$P/var/lib/dpkg" --configure -a 2>&1
rt=$?
apt-get -f install -y 2>&1
exit $((rt ? rt : $?))
EOF
chmod +x "$RUNTIME/usr/bin/apt-fix"
echo "  + apt-fix（broken state 一键修复）"
echo "» 拷贝 apt 下载 methods（http/file/copy/rred/store…）"
for m in "$PREFIX"/lib/apt/methods/*; do
  [ -f "$m" ] || continue
  copy_elf_with_deps "$m" "$RUNTIME/usr/lib/apt/methods/$(basename "$m")" "apt-method-$(basename "$m")"
done
echo "» 拷贝 termux-keyring 公钥（dpkg -L termux-keyring 实测路径）…"
if dpkg -L termux-keyring >/dev/null 2>&1; then
  while IFS= read -r kg; do
    [ -f "$kg" ] || continue
    cp -L "$kg" "$RUNTIME/usr/etc/apt/trusted.gpg.d/"
    echo "  + $(basename "$kg")"
  done < <(dpkg -L termux-keyring 2>/dev/null | grep '\.gpg$' || true)
fi
# 若 termux-keyring 另装 share/keyring（旧版布局），一并带上（不存在则跳过）
if [ -d "$PREFIX/share/keyring" ]; then
  mkdir -p "$RUNTIME/usr/share"
  cp -rL "$PREFIX/share/keyring" "$RUNTIME/usr/share/"
fi
# keyring 是 apt update 验签的前提：一张公钥都没有就应 fail loud（防静默拿到坏包源）
if ! find "$RUNTIME/usr/etc/apt/trusted.gpg.d" -type f -print -quit | grep -q .; then
  echo "✗ termux-keyring 未找到/未装，apt 验签缺失（请 pkg install termux-keyring）" >&2
  exit 1
fi
echo "» 准备 meow-exec.so（execve 转发 LD_PRELOAD）…"
if [ -n "${MEOW_EXEC_SO:-}" ] && [ -f "$MEOW_EXEC_SO" ]; then
  # 预编译 .so（如 PC NDK 交叉编译产物）优先，省去 Termux 装 clang；仍保留源码编译路径
  cp -L "$MEOW_EXEC_SO" "$RUNTIME/lib/meow-exec.so"
  echo "  ✓ 使用预编译 meow-exec.so: $MEOW_EXEC_SO"
else
  MEOW_EXEC_SRC="$(cd "$(dirname "$0")" && pwd)/meow-exec.c"
  if [ -f "$MEOW_EXEC_SRC" ]; then
    command -v clang >/dev/null || { echo "✗ 未找到 clang，请先 pkg install clang（或用 MEOW_EXEC_SO 指定预编译 .so）" >&2; exit 1; }
    clang -shared -fPIC -O2 -o "$RUNTIME/lib/meow-exec.so" "$MEOW_EXEC_SRC" -ldl
  else
    echo "✗ 找不到 meow-exec.c（$MEOW_EXEC_SRC）" >&2
    exit 1
  fi
fi
ls -lh "$RUNTIME/lib/meow-exec.so"

# ── 3. DSH 闭包（PC 端 pnpm deploy 产物：node_modules + dsh/）──
echo "» 解包 DSH 闭包…"
tar -C "$RUNTIME" -xzf "$CLOSURE_TGZ"
[ -f "$RUNTIME/dsh/cordis.yml" ] || { echo "✗ 闭包缺 dsh/cordis.yml" >&2; exit 1; }
[ -f "$RUNTIME/dsh/host.mjs" ] || \
  { echo "✗ 闭包缺 dsh/host.mjs（喵仓宿主入口，替代已删除的 jsonrpc-demo/packaged-bin.js）" >&2; exit 1; }

# ── 3.5 真终端：node-pty Android fork + terminal-host ──
# 官方 node-pty 在 Android 编译失败，用预编译 arm64 fork（无需编译工具链）
echo "» 安装 node-pty Android fork…"
PTY_STAGE="$STAGE/pty"
mkdir -p "$PTY_STAGE" "$RUNTIME/node_modules/@mmmbuto"
( cd "$PTY_STAGE" && npm install @mmmbuto/node-pty-android-arm64 --no-save --omit=dev >/dev/null 2>&1 ) || \
  { echo "✗ node-pty fork 安装失败" >&2; exit 1; }
cp -rL "$PTY_STAGE/node_modules/@mmmbuto/node-pty-android-arm64" "$RUNTIME/node_modules/@mmmbuto/"
# DSH 的 subprocess 插件依赖「官方 node-pty」，但它没有 Android arm64 预编译；
# 把 fork 的 pty.node 复制到官方 node-pty 的 prebuilds/android-arm64/，让官方 node-pty 也能加载
# （hoisted 布局 node-pty 在顶层；isolated 布局在 .pnpm 槽位）
NODE_PTY_DIR=""
if [ -d "$RUNTIME/node_modules/node-pty" ]; then
  NODE_PTY_DIR="$RUNTIME/node_modules/node-pty"
else
  NODE_PTY_DIR=$(find "$RUNTIME/node_modules/.pnpm" -maxdepth 4 -type d -path "*/node_modules/node-pty" 2>/dev/null | head -1)
fi
if [ -n "$NODE_PTY_DIR" ]; then
  mkdir -p "$NODE_PTY_DIR/prebuilds/android-arm64"
  cp "$RUNTIME/node_modules/@mmmbuto/node-pty-android-arm64/prebuilds/android-arm64/pty.node" "$NODE_PTY_DIR/prebuilds/android-arm64/pty.node"
  echo "  ✓ 官方 node-pty 已接入 Android pty.node"
fi
# terminal-host.js 拷到 runtime/bin/（真终端宿主，由 App 经 linker64 用 node 拉起）
cp "$(cd "$(dirname "$0")" && pwd)/terminal-host.js" "$RUNTIME/bin/terminal-host.js"

# ── 4. TLS 证书（Termux 版 node 的 OpenSSL 默认 CA 路径在 Termux 私有目录，
#       App 沙箱读不到，必须内置 CA 束；App 端用 OPENSSL_CONF/NODE_EXTRA_CA_CERTS 指过来）──
echo "» 拷贝 CA 证书束（etc/tls/cert.pem）…"
mkdir -p "$RUNTIME/etc/tls"
cp -L "$PREFIX/etc/tls/cert.pem" "$RUNTIME/etc/tls/cert.pem"
: > "$RUNTIME/etc/tls/openssl.cnf"  # 空配置即可，避开 Termux 默认 openssl.cnf 路径不可读报错

# ── 5. DNS 兜底 shim（App 沙箱内 getaddrinfo 走不了 netd 解析，见 dns-shim.js；
#       App 端通过 NODE_OPTIONS --require lib/dns-shim.js 注入）──
echo "» 拷贝 dns-shim.js…"
cp "$(cd "$(dirname "$0")" && pwd)/dns-shim.js" "$RUNTIME/lib/dns-shim.js"

# ── 5.5 物化 symlink（Android SELinux 禁 createSymbolicLink，App 端解压会丢链接；
#        pnpm .pnpm 布局依赖相对 symlink，这里把全部链接解引用成真实拷贝）──
echo "» 物化 node_modules symlink…"
node -e '
const fs = require("fs"), path = require("path");
const root = process.argv[1];
function copyReal(src, dest) {
  const st = fs.lstatSync(src);
  if (st.isSymbolicLink()) { copyReal(path.resolve(path.dirname(src), fs.readlinkSync(src)), dest); return; }
  if (st.isDirectory()) { fs.mkdirSync(dest, { recursive: true }); for (const n of fs.readdirSync(src)) copyReal(path.join(src, n), path.join(dest, n)); return; }
  fs.copyFileSync(src, dest);
}
function walk(dir) {
  for (const n of fs.readdirSync(dir)) {
    const full = path.join(dir, n);
    let st; try { st = fs.lstatSync(full); } catch { continue; }
    if (st.isSymbolicLink()) {
      const t = path.resolve(path.dirname(full), fs.readlinkSync(full));
      fs.unlinkSync(full);
      if (fs.existsSync(t)) copyReal(t, full);
    } else if (st.isDirectory()) walk(full);
  }
}
walk(root);
console.log("  ✓ symlink 物化完成");
' "$RUNTIME/node_modules"

# ── 6. 打包（gzip 流；.bin 后缀避开 AGP 对 .gz 的自动解压改名）──
echo "» 打包前自检（apt 包管理器产物）…"
[ -f "$RUNTIME/usr/bin/apt-get" ] || { echo "✗ 缺 $RUNTIME/usr/bin/apt-get" >&2; exit 1; }
[ -f "$RUNTIME/usr/bin/dpkg" ] || { echo "✗ 缺 $RUNTIME/usr/bin/dpkg" >&2; exit 1; }
[ -f "$RUNTIME/usr/bin/gpgv" ] || { echo "✗ 缺 $RUNTIME/usr/bin/gpgv" >&2; exit 1; }
[ -f "$RUNTIME/usr/bin/apt-key" ] || { echo "✗ 缺 $RUNTIME/usr/bin/apt-key（apt update 验签必需）" >&2; exit 1; }
[ -f "$RUNTIME/lib/meow-exec.so" ] || { echo "✗ 缺 $RUNTIME/lib/meow-exec.so" >&2; exit 1; }
[ -f "$RUNTIME/usr/lib/apt/methods/http" ] || { echo "✗ 缺 apt methods/http" >&2; exit 1; }
echo "  ✓ apt/dpkg/gpgv/meow-exec/methods 齐备"
echo "» 打包 tar.gz…"
tar -C "$STAGE" -czf "$OUT_FILE" meow-runtime
ls -lh "$OUT_FILE"

cat <<EOF

✅ 完成！把 runtime.bin 拷回仓库：
   adb pull 路径见你习惯的 adb/ssh 中转（参考 plan/plan-phase1.md 七节）
   目标：android-app/app/src/main/assets/runtime.bin
EOF
