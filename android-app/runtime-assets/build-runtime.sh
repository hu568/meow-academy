#!/usr/bin/env bash
# ============================================================
# build-runtime.sh —— 打包喵仓运行时（DSH 版，阶段 1）
#
# 在真机 Termux 上运行（需 nodejs-lts + binutils + bash）：
#   1. 拷贝 node + bash 二进制及其 Termux 动态库（cp -L 解引用 symlink）
#   2. 解包 PC 端 build-dsh-closure.sh 生成的 DSH 闭包（node_modules + dsh/）
#   3. 拷贝 CA 束 + DNS shim（旧坑原样保留）
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
  echo "» 拷贝 $3 二进制…"
  cp -L "$src" "$dst"
  "$READELF" -d "$dst" | sed -n 's/.*NEEDED.*\[\([^]]*\)\].*/\1/p' > "$need_file"
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

# ── 3. DSH 闭包（PC 端 pnpm deploy 产物：node_modules + dsh/）──
echo "» 解包 DSH 闭包…"
tar -C "$RUNTIME" -xzf "$CLOSURE_TGZ"
[ -f "$RUNTIME/dsh/cordis.yml" ] || { echo "✗ 闭包缺 dsh/cordis.yml" >&2; exit 1; }
[ -f "$RUNTIME/node_modules/@deepseek-ai/dsh-sdk-jsonrpc-demo/lib/packaged-bin.js" ] || \
  { echo "✗ 闭包缺 jsonrpc-demo/lib/packaged-bin.js" >&2; exit 1; }

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
echo "» 打包 tar.gz…"
tar -C "$STAGE" -czf "$OUT_FILE" meow-runtime
ls -lh "$OUT_FILE"

cat <<EOF

✅ 完成！把 runtime.bin 拷回仓库：
   adb pull 路径见你习惯的 adb/ssh 中转（参考 plan/plan-phase1.md 七节）
   目标：android-app/app/src/main/assets/runtime.bin
EOF
