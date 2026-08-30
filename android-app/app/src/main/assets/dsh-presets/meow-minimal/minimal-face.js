/**
 * minimal-face —— meow-minimal 组合内插件：按会话裁剪基座全局工具行（plan-minimal-mode §三）。
 *
 * preset 挂载只会「加」行，基座全局注册的 tool-fs / tool-todo / tool-web 减不掉；
 * 本插件随预设挂载、实例化在会话作用域（与 meow-standard 工具行同层），
 * 调 tools.restrict({allow}) 把该会话的全局工具裁到只剩 allow 名单：
 *   - restrict 要求 scoped ctx（全局调用直接 throw，tools/src/index.ts:1074）；
 *   - 只过滤全局注册工具，组合内 scoped 注册（持久 bash）不受影响（:677）；
 *   - 随会话销毁经 scope 释放，无需手动 dispose。
 * 零 import：inject 按服务 id 字符串解析（bare specifier 才走闭包 node_modules）。
 */

export const name = 'minimal-face'

/** tools = ToolRuntime 工具注册表服务（基座全局，随 chain 解析） */
export const inject = ['tools']

/** 极简模式面：持久 bash（本组合 scoped 注册遮蔽全局一次性行）+ str_replace_editor（基座全局行放行） */
const MINIMAL_TOOLS = ['bash', 'str_replace_editor']

export function apply(ctx) {
  ctx.tools.restrict({ allow: MINIMAL_TOOLS })
}
