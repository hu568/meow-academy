#!/usr/bin/env bash
# ============================================================
# build-dsh-closure.sh —— 在 PC 上生成 DSH 运行时闭包（阶段 1 前置）
#
# 产物：单个 gzip 流 <输出>.tar.gz，内含 node_modules/ 与 dsh/ 两个顶层目录，
#       由真机 Termux 的 build-runtime.sh 解包进 meow-runtime/。
#
# 流程：
#   1. pnpm deploy --prod --legacy --config.link-workspace-packages=false
#      （workspace 包按 files 字段真实拷贝，只留 lib/ 不拷 src/）
#   2. node fix-closure-links.mjs：把指向 DSH checkout 的绝对 symlink
#      （pnpm link: 覆写的 vendor 包等）改写为闭包内相对链接
#   3. 拷入 runtime-assets/dsh/（cordis.yml + meow-extensions 自定义插件）
#   4. tar czf 打包（保持 pnpm 的 .pnpm 布局 + 相对 symlink）
#
# 前置：PC 有 node + pnpm；DSH checkout 需先 pnpm install 过，
#       且 deploy/meow-runtime/ 已在 pnpm-workspace.yaml 中注册。
# 用法：bash build-dsh-closure.sh [DSH checkout 路径] [输出文件]
# ============================================================
set -euo pipefail

DSH_ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)/dsh}"
# 默认落到仓库根 .tmp/（临时产物不入库；release/ 只放 APK）
OUT_FILE="${2:-$(cd "$(dirname "$0")/../.." && pwd)/.tmp/dsh-closure.tar.gz}"
OUT_DIR="$(dirname "$OUT_FILE")"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DSH_DIR="$SCRIPT_DIR/dsh"

command -v node >/dev/null || { echo "✗ 未找到 node" >&2; exit 1; }
command -v pnpm >/dev/null || { echo "✗ 未找到 pnpm" >&2; exit 1; }
[ -d "$DSH_ROOT/deploy/meow-runtime" ] || { echo "✗ 找不到清单 $DSH_ROOT/deploy/meow-runtime" >&2; exit 1; }
[ -f "$DSH_DIR/cordis.yml" ] || { echo "✗ 找不到 $DSH_DIR/cordis.yml" >&2; exit 1; }

echo "» DSH checkout : $DSH_ROOT"
echo "» 输出         : $OUT_FILE"

# ── 1. pnpm deploy（临时目录，每次全新生成）──
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
DEPLOY_DIR="$STAGE/runtime"
echo "» pnpm deploy（约 1-2 分钟）…"
cd "$DSH_ROOT"
pnpm --filter ./deploy/meow-runtime deploy --prod --legacy \
  --config.link-workspace-packages=false --config.node-linker=hoisted "$DEPLOY_DIR" >/dev/null 2>&1 || \
  { echo "✗ pnpm deploy 失败" >&2; exit 1; }

# ── 2. 链接归一化（逃逸链接 → 闭包内相对链接 / 物化 vendor 包）──
echo "» 链接归一化…"
node "$SCRIPT_DIR/tools/fix-closure-links.mjs" "$DEPLOY_DIR/node_modules" || {
  echo "✗ 链接归一化失败" >&2; exit 1
}

# ── 2.5 node-prune 裁剪（tests/docs/示例等，失败仅警告，不阻塞）──
if command -v npm >/dev/null 2>&1; then
  echo "» node-prune 裁剪 node_modules…"
  timeout 90 npx --yes node-prune "$DEPLOY_DIR/node_modules" >/dev/null 2>&1 || \
    echo "  ⚠ node-prune 失败/超时，继续（体积会偏大）"
fi

# ── 3. 拷入喵学堂组合（cordis.yml + meow-extensions）──
echo "» 拷入 dsh/ 组合…"
cp -r "$DSH_DIR" "$DEPLOY_DIR/dsh"

# ── 4. 打包（顶层条目：node_modules/ + dsh/；保持相对 symlink）──
echo "» tar czf…"
mkdir -p "$OUT_DIR"
# GNU tar 会把 E:/xxx 当远程主机（rsh 语法），必须 cd 后用纯文件名
( cd "$OUT_DIR" && tar -C "$DEPLOY_DIR" -czf "$(basename "$OUT_FILE")" node_modules dsh )
ls -lh "$OUT_FILE"

cat <<EOF

✅ 完成！真机打包时把 $OUT_FILE 推到 Termux：
   adb push $OUT_FILE /data/local/tmp/
   adb shell 'cp /data/local/tmp/$(basename "$OUT_FILE") ~/'
   然后运行 android-app/runtime-assets/build-runtime.sh ~/$(basename "$OUT_FILE")
EOF