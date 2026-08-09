import { builtinModels } from '@earendil-works/pi-ai/providers/all';
import type { AssistantMessageEventStream } from '@earendil-works/pi-ai';
import { config } from './config';

/**
 * Pi-AI 统一 LLM 网关
 * 基于 @earendil-works/pi-ai 的 builtinModels:
 * 一个 Models 集合注册了 OpenAI/Anthropic/DeepSeek/Google 等全部内置 provider,
 * 通过 models.streamSimple() 统一流式调用, 自动处理鉴权/费用统计/模型路由。
 */
const models = builtinModels();

/** 默认聊天模型 */
export function getChatModel() {
  const m = models.getModel(config.chatModel.provider, config.chatModel.model);
  if (!m) {
    throw new Error(
      `模型 ${config.chatModel.provider}/${config.chatModel.model} 未找到。` +
      `可用模型: ${models.getModels().slice(0, 8).map(x => x.id).join(', ')} ...`
    );
  }
  return m;
}

/** 列出可用模型(带鉴权状态) */
export async function listModels() {
  const out: Array<{ provider: string; model: string; auth: boolean }> = [];
  for (const m of models.getModels()) {
    try {
      const auth = await models.checkAuth(m);
      out.push({ provider: m.provider, model: m.id, auth: !!auth });
    } catch {
      out.push({ provider: m.provider, model: m.id, auth: false });
    }
  }
  return out;
}

/** 检测默认模型鉴权是否就绪 */
export async function isChatReady() {
  try {
    const m = getChatModel();
    return !!(await models.checkAuth(m));
  } catch {
    return false;
  }
}

/**
 * 流式对话 (OpenAI 兼容消息格式)
 * @param messages [{role, content}]
 * @returns 事件流, 提取 text_delta 即可得到增量文本
 */
export function streamChat(
  messages: Array<{ role: string; content: string }>,
  opts?: { model?: string; reasoning?: string }
): AssistantMessageEventStream {
  const model = opts?.model
    ? models.getModel(config.chatModel.provider, opts.model) ?? getChatModel()
    : getChatModel();

  const context = {
    messages: messages.map((m) => ({
      role: m.role as 'user' | 'assistant' | 'system',
      content: m.content,
      timestamp: Date.now(),
    })),
  };

  return models.streamSimple(model, context, {
    reasoning: (opts?.reasoning as never) ?? undefined,
  });
}

/** 非流式对话 (测试用) */
export async function completeChat(
  messages: Array<{ role: string; content: string }>
) {
  const model = getChatModel();
  const context = {
    messages: messages.map((m) => ({
      role: m.role as 'user' | 'assistant' | 'system',
      content: m.content,
      timestamp: Date.now(),
    })),
  };
  const msg = await models.completeSimple(model, context);
  const text = msg.content.filter((c) => c.type === 'text').map((c) => c.text).join('');
  return { text, msg };
}
