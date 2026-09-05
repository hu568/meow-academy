/**
 * 思考强度钳制（plan-meow-jsonrpc-refactor 第 4 步自 meow-jsonrpc.js 外迁）。
 * 纯函数、无 cordis 依赖：llm 服务经参数注入；留痕走 ./log.js（warn 通道）。
 */

import { warn } from './log.js'

/**

 * 把思考强度钳制到目标模型的能力范围内（防止 DeepSeek 的 'high' 被原样带到
 * 不支持思考的 OpenAI 兼容模型上，导致请求以 UNSUPPORTED_REASONING_EFFORT 失败）。
 *
 * 关键：核心 llm 服务（resolveCallFor）对「无思考能力」的模型（reasoning 元数据
 * 缺失，如自定义 provider 的模型）会拒绝**任何**显式强度——包括 'off'。所以：
 *   - 模型支持该强度 → 原样保留；
 *   - 模型无思考能力或不支持该强度 → **不传**（undefined，交 provider 默认/不思考），
 *     而不是退回 'off'（那同样会被核心校验拒绝）；
 *   - 模型支持思考且有无默认强度 → 用模型默认（前提是默认也在支持列表里）。
 * 查询失败（provider 未注册/模型不存在等）→ 原样保留，把真实错误留给上游，不掩盖。
 * @param llm - llm 服务（可能 undefined）
 * @param provider - 目标 provider 路由
 * @param model - 目标模型 id
 * @param effort - 期望思考强度（undefined = 不指定，交给 provider 默认）
 * @returns {{ effort?: string, modelReasoning?: { efforts: string[], defaultEffort?: string } }}
 */
export async function clampReasoningEffort(llm, provider, model, effort) {
  if (effort === undefined) return { effort: undefined, modelReasoning: undefined }
  let info
  try {
    if (llm === undefined) return { effort, modelReasoning: undefined }
    info = await llm.resolveModelInfo(provider, model)
  } catch (error) {
    // 查询失败原样保留 effort、把真实错误留给上游（KDoc 已声明不掩盖）；留痕便于定位
    warn(`思考强度查询失败（effort 原样保留）provider=${provider} model=${model}`, error)
    return { effort, modelReasoning: undefined }
  }
  const reasoning = info?.reasoning
  if (reasoning === undefined) {
    // 模型无思考能力：任何显式强度（含 off）都会被核心校验拒绝 → 不传
    return { effort: undefined, modelReasoning: undefined }
  }
  const efforts = reasoning.efforts.map((entry) => String(entry.id))
  const modelReasoning = {
    efforts,
    ...(reasoning.defaultEffort === undefined ? {} : { defaultEffort: String(reasoning.defaultEffort) }),
  }
  if (efforts.includes(effort)) return { effort, modelReasoning }
  // 不支持当前强度 → 模型默认强度（且必须在支持列表里），否则不传
  const fallback = reasoning.defaultEffort === undefined ? undefined : String(reasoning.defaultEffort)
  if (fallback !== undefined && efforts.includes(fallback)) return { effort: fallback, modelReasoning }
  return { effort: undefined, modelReasoning }
}
