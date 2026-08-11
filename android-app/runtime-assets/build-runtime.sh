#!/usr/bin/env bash
# ============================================================
# build-runtime.sh —— 打包 Pi 运行时（M2.2）
#
# 在真机 Termux 上运行（需已装 node + pi-coding-agent，见 docs/plan-phase1.md M2.0）：
#   1. 拷贝 node 二进制 + 其 Termux 动态库（cp -L 解引用 symlink）
#   2. 拷贝 pi-coding-agent 全局安装目录（lib/node_modules/...）
#   3. node-prune 裁剪 node_modules 里的冗余文件（测试/文档/源码 map 等）
#   4. tar czf 打包为 meow-runtime/ 顶层目录的 gzip 流 → runtime.bin
#
# 产物拷回 PC 后放到 app/src/main/assets/runtime.bin（gitignore 已排除，
# 由本脚本生成）。RuntimeExtractor 解压时剥离 meow-runtime/ 前缀。
#
# 用法： bash build-runtime.sh [输出目录=脚本同级]
#   输出： <输出目录>/runtime.bin
# ============================================================
set -euo pipefail

OUT_DIR="${1:-$(cd "$(dirname "$0")" && pwd)}"
OUT_FILE="$OUT_DIR/runtime.bin"

# ── 0. 环境检查 ──
command -v node >/dev/null || { echo "✗ 未找到 node，请先 pkg install nodejs" >&2; exit 1; }
command -v gzip >/dev/null || { echo "✗ 未找到 gzip" >&2; exit 1; }

NODE_BIN="$(command -v node)"
PI_BIN="$(command -v pi || true)"
PI_PKG_DIR="$(npm root -g 2>/dev/null)/@earendil-works/pi-coding-agent"

if [ -z "$PI_BIN" ]; then
  echo "✗ 未全局安装 pi（npm i -g --ignore-scripts @earendil-works/pi-coding-agent）" >&2
  exit 1
fi
[ -d "$PI_PKG_DIR" ] || { echo "✗ 找不到 pi 包目录：$PI_PKG_DIR" >&2; exit 1; }
[ -f "$PI_PKG_DIR/dist/cli.js" ] || { echo "✗ pi 包缺少 dist/cli.js" >&2; exit 1; }

echo "» node    : $NODE_BIN"
echo "» pi      : $PI_BIN"
echo "» pi 包目录: $PI_PKG_DIR"
echo "» 输出     : $OUT_FILE"

# ── 1. 暂存目录布局（与 App 端 RuntimeExtractor 期望一致） ──
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
RUNTIME="$STAGE/meow-runtime"
mkdir -p "$RUNTIME/bin" "$RUNTIME/lib"

# ── 2. node 二进制 + 依赖动态库（cp -L 解引用 Termux 的 symlink） ──
echo "» 拷贝 node 二进制…"
cp -L "$NODE_BIN" "$RUNTIME/bin/node"
echo "» 解析并拷贝 node 动态库（LD_LIBRARY_PATH=runtime/lib 需要）…"
# Termux 没有 ldd，用 binutils 的 greadelf 枚举 NEEDED（pkg install binutils）；
# 迭代补齐传递依赖（如 libicui18n → libicuuc）；系统库（libc/libm/libdl）
# 不在 $PREFIX/lib 下，自动跳过，由 linker64 在运行时解析。
READELF="$(command -v greadelf || command -v readelf || true)"
[ -n "$READELF" ] || { echo "✗ 缺少 readelf/greadelf，请 pkg install binutils" >&2; exit 1; }
NEED_FILE="$STAGE/need.txt"
"$READELF" -d "$RUNTIME/bin/node" | sed -n 's/.*NEEDED.*\[\([^]]*\)\].*/\1/p' > "$NEED_FILE"
while :; do
  added=0
  while read -r lib; do
    [ -n "$lib" ] || continue
    [ -f "$RUNTIME/lib/$lib" ] && continue
    if [ -f "$PREFIX/lib/$lib" ]; then
      cp -L "$PREFIX/lib/$lib" "$RUNTIME/lib/"
      "$READELF" -d "$PREFIX/lib/$lib" 2>/dev/null | \
        sed -n 's/.*NEEDED.*\[\([^]]*\)\].*/\1/p' >> "$NEED_FILE"
      added=1
    fi
  done < "$NEED_FILE"
  [ "$added" -eq 0 ] && break
done
echo "  lib/ 现有 $(ls "$RUNTIME/lib" | grep -c '\.so') 个 .so"

# ── 2.5 TLS 证书（Termux 版 node 的 OpenSSL 默认 CA 路径在 Termux 私有目录，
#       App 沙箱读不到，必须内置 CA 束；App 端用 OPENSSL_CONF/NODE_EXTRA_CA_CERTS 指过来） ──
echo "» 拷贝 CA 证书束（etc/tls/cert.pem）…"
mkdir -p "$RUNTIME/etc/tls"
cp -L "$PREFIX/etc/tls/cert.pem" "$RUNTIME/etc/tls/cert.pem"
: > "$RUNTIME/etc/tls/openssl.cnf"  # 空配置即可，避开 Termux 默认 openssl.cnf 路径不可读报错

# ── 3. pi-coding-agent 整体（含 node_modules；保留 @earendil-works scope 目录，
#       与 App 端 PiProcessLauncher 期望路径一致） ──
echo "» 拷贝 pi-coding-agent…"
mkdir -p "$RUNTIME/lib/node_modules/@earendil-works"
cp -rL "$PI_PKG_DIR" "$RUNTIME/lib/node_modules/@earendil-works/"
[ -f "$RUNTIME/lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js" ] || \
  { echo "✗ 拷贝后缺 dist/cli.js" >&2; exit 1; }
# 依赖裁剪：node-prune 删除 tests/docs/示例等（App 只需 cli.js 运行路径）
if command -v npm >/dev/null 2>&1; then
  echo "» node-prune 裁剪 node_modules…"
  npx --yes node-prune "$RUNTIME/lib/node_modules" >/dev/null 2>&1 || \
    echo "  ⚠ node-prune 失败，继续（体积会偏大）"
fi

# ── 3.5 DNS 兜底 shim（App 沙箱内 getaddrinfo 走不了 netd 解析，见 dns-shim.js；
#        App 端通过 NODE_OPTIONS --require lib/dns-shim.js 注入） ──
echo "» 拷贝 dns-shim.js…"
cp "$(cd "$(dirname "$0")" && pwd)/dns-shim.js" "$RUNTIME/lib/dns-shim.js"

# ── 4. 打包（gzip 流；.bin 后缀避开 AGP 对 .gz 的自动解压改名） ──
echo "» 打包 tar.gz…"
tar -C "$STAGE" -czf "$OUT_FILE" meow-runtime
ls -lh "$OUT_FILE"

cat <<EOF

✅ 完成！把 runtime.bin 拷回仓库：
   scp -P 8022 u0_a169@<真机IP>:$(pwd)/$OUT_FILE /e/meow-academy/android-app/app/src/main/assets/runtime.bin
EOF