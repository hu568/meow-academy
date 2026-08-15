#!/usr/bin/env node
/**
 * fix-closure-links.mjs —— 把 pnpm deploy 闭包里的符号链接全部改写成「闭包内自洽」形态。
 *
 * pnpm deploy --legacy 在 Windows 上会为两类依赖写出指向 checkout 的链接：
 *   1. overrides 钉到 link:vendor/... 的包（cosmokit、schemastery）→ 绝对路径指向 checkout vendor；
 *   2. 声明了但未纳入 deploy 闭包的依赖（optional/peer 未物化项）→ 绝对/相对指向 checkout store，
 *      在闭包内是死链接。
 * 处理规则（按链接目标分类）：
 *   - 目标已落在闭包内且存在        → 原样保留；
 *   - 目标含 .pnpm 段（store 路径） → 重映射到闭包 .pnpm 同槽位；槽位存在改相对链接，不存在删除死链；
 *   - 其它逃逸目标（vendor 等）      → 物化到 node_modules/.meow-vendor/<名>-<hash> 并改相对链接
 *                                      （同一目标只物化一份，内部链接递归套用本规则）。
 * 用法：node fix-closure-links.mjs <closure/node_modules>
 */
import {
  existsSync, lstatSync, mkdirSync, readdirSync, readlinkSync,
  symlinkSync, copyFileSync, unlinkSync, rmSync,
} from 'node:fs'
import { createHash } from 'node:crypto'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'

const root = resolve(process.argv[2] ?? 'node_modules')
if (!existsSync(root)) { console.error('✗ closure 目录不存在:', root); process.exit(1) }
// deploy 根包自身的自链（.pnpm/node_modules/<根包名> → 清单源目录）：运行时无人 import，直接删除
const rootPackageName = process.argv[3] ?? 'meow-academy-dsh-runtime'

const STASH = join(root, '.meow-vendor')
const copiedTargets = new Map()
let stats = { kept: 0, remapped: 0, dropped: 0, materialized: 0 }

function isUnder(p, base) { return p === base || p.startsWith(base + sep) }

/** 把「任意绝对/相对目标」折算成绝对路径 */
function absolute(raw, fromDir) { return isAbsolute(raw) ? raw : resolve(fromDir, raw) }

/** 目标若含 .pnpm 段：映射到闭包 .pnpm 的同后缀路径 */
function mapToClosureStore(target) {
  const marker = sep + '.pnpm' + sep
  const idx = target.indexOf(marker)
  if (idx < 0) return undefined
  return join(root, '.pnpm', target.slice(idx + marker.length))
}

/** 物化逃逸目标（幂等）。返回 stash 内路径 */
function materialize(target) {
  const hit = copiedTargets.get(target)
  if (hit !== undefined) return hit
  const name = basename(target) + '-' + createHash('sha1').update(target).digest('hex').slice(0, 8)
  const dest = join(STASH, name)
  mkdirSync(STASH, { recursive: true })
  if (!existsSync(dest)) copyNormalized(target, dest)
  copiedTargets.set(target, dest)
  return dest
}

/** 递归复制：内部链接套用同一套规则（store 重映射 / 死链跳过 / 逃逸物化） */
function copyNormalized(src, dest) {
  const st = lstatSync(src)
  if (st.isSymbolicLink()) {
    const raw = readlinkSync(src)
    const target = absolute(raw, dirname(src))
    const mapped = mapToClosureStore(target)
    if (mapped !== undefined && existsSync(mapped)) {
      symlinkSync(relative(dirname(dest), mapped), dest)
    } else if (mapped !== undefined) {
      return // 死链：跳过（依赖未部署，运行时不会解析到）
    } else if (isUnder(target, root) && !existsSync(target)) {
      return // 闭包内死链：跳过
    } else if (!isUnder(target, root)) {
      const mat = materialize(target)
      symlinkSync(relative(dirname(dest), mat), dest)
    } else {
      symlinkSync(raw, dest)
    }
    return
  }
  if (st.isDirectory()) {
    mkdirSync(dest, { recursive: true })
    for (const name of readdirSync(src)) copyNormalized(join(src, name), join(dest, name))
    return
  }
  copyFileSync(src, dest)
}

/** 处理一个链接条目（闭包内 walk 与物化复制共用） */
function fixLink(full) {
  const raw = readlinkSync(full)
  const target = absolute(raw, dirname(full))
  if (basename(full) === rootPackageName && lstatSync(full).isSymbolicLink() && !isUnder(target, root)) {
    unlinkSync(full)
    stats.dropped += 1
    console.log('  drop self-link:', relative(root, full))
    return
  }
  if (isUnder(target, root)) {
    if (existsSync(target)) { stats.kept += 1; return }
    // 闭包内死链：删除（依赖未部署，无解析可能）
    unlinkSync(full)
    stats.dropped += 1
    console.log('  drop dead:', relative(root, full))
    return
  }
  const mapped = mapToClosureStore(target)
  if (mapped !== undefined) {
    if (existsSync(mapped)) {
      unlinkSync(full)
      symlinkSync(relative(dirname(full), mapped), full)
      stats.remapped += 1
      console.log('  remap:', relative(root, full), '→', relative(dirname(full), mapped))
    } else {
      unlinkSync(full)
      stats.dropped += 1
      console.log('  drop dead:', relative(root, full))
    }
    return
  }
  // 非 store 逃逸目标（vendor 目录等）：物化
  const mat = materialize(target)
  unlinkSync(full)
  symlinkSync(relative(dirname(full), mat), full)
  stats.materialized += 1
  console.log('  materialize:', relative(root, full), '→', relative(dirname(full), mat))
}

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (full === STASH) continue
    let st
    try { st = lstatSync(full) } catch { continue }
    if (st.isSymbolicLink()) fixLink(full)
    else if (st.isDirectory()) walk(full)
  }
}

rmSync(STASH, { recursive: true, force: true })
walk(root)
console.log('✅ 完成：保留', stats.kept, '· 重映射', stats.remapped, '· 删死链', stats.dropped, '· 物化', stats.materialized)