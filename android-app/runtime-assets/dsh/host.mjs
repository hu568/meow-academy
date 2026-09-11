#!/usr/bin/env node
/**
 * 喵仓（meow-academy）安卓运行时宿主入口。
 *
 * 替代上游 0.1.5 删除的 `packages/examples/jsonrpc-demo/packaged-bin.js`：
 * 以本文件（闭包内 dsh/ 目录）为 bare 模块基准启动显式指定的 cordis.yml，
 * 并持有进程生命周期 —— stdin EOF / SIGTERM / SIGINT 时先 dispose 插件树再退出。
 * 上游 0.1.5 只留 `apps/cli`（profile 驱动、需要 dsh 配置文件树）作为 bin，喵仓
 * 需要「显式配置文件 + 闭包内解析」的入口，故在这里复刻原 runner.ts 的语义。
 * 见 plan/plan-dsh-upgrade-0.1.5.md §2.3 与 android-app/runtime-assets/dsh-fork/README.md。
 *
 * 用法：node <runtime>/dsh/host.mjs <config.yml>（DSH_CORDIS_CONFIG 环境变量优先）
 */
import { existsSync } from 'node:fs'
import { boot, installFailLoud, loadEnv, resolveConfigPath } from '@deepseek-ai/dsh-app-boot'

const NAME = 'meow-dsh-host'

installFailLoud(NAME)
loadEnv(NAME)

// Env 优先于 argv；空值等于没传。显式配置是必需项，没有内置回退。
const fromEnv = process.env['DSH_CORDIS_CONFIG']
const fromArgv = process.argv[2]
const requested = fromEnv !== undefined && fromEnv !== ''
  ? fromEnv
  : fromArgv !== undefined && fromArgv !== '' ? fromArgv : undefined
const configPath = requested === undefined ? undefined : resolveConfigPath(requested, undefined)
if (configPath === undefined || !existsSync(configPath)) {
  process.stderr.write(
    `usage: ${NAME} <path/to/cordis.yml> (or set DSH_CORDIS_CONFIG=<path>, which wins);`
    + ' the config is required — there is no built-in fallback\n',
  )
  process.exit(1)
}

// bareModuleBaseUrl = 本文件 URL：bare 包名从闭包的 node_modules 解析（相对入口
// 仍相对配置文件解析，由 Loader 的 include 语义保证）。
const ctx = await boot(NAME, configPath, undefined, undefined, import.meta.url)
let exiting = false

async function disposeAndExit(code) {
  if (exiting) return
  exiting = true
  try {
    await ctx.fiber.dispose()
  } finally {
    process.exit(code)
  }
}

process.stdin.on('end', () => { void disposeAndExit(0) })
process.on('SIGTERM', () => { void disposeAndExit(0) })
process.on('SIGINT', () => { void disposeAndExit(130) })
