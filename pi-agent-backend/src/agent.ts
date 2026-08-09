import { Agent } from '@earendil-works/pi-agent-core';
import { Type } from '@earendil-works/pi-ai';
import { getChatModel } from './llm';
import { ragStore } from './rag';

/**
 * Pi-Agent 智能体封装
 * 基于 @earendil-works/pi-agent-core:
 * Agent 类 + streamFn 实现工具调用、状态管理、流式响应
 *
 * 内置工具:
 * - rag_search: 检索学习知识库 (RAG)
 * - get_time: 获取当前时间
 */
export async function createStudyAgent(systemPrompt?: string) {
  const model = getChatModel();

  const tools = [
    {
      name: 'rag_search',
      description: '从喵学堂学习知识库中检索与问题最相关的知识片段, 返回文本片段列表',
      parameters: Type.Object({
        query: Type.String({ description: '要检索的问题或关键词' }),
        topK: Type.Optional(Type.Number({ description: '返回片段数, 默认5' })),
      }),
      execute: async ({ query, topK }: { query: string; topK?: number }) => {
        const hits = await ragStore.retrieve(query, topK ?? 5);
        return {
          content: hits.map((h, i) =>
            `[片段${i + 1} | 来源:${h.item.source} | 相关度:${h.score.toFixed(3)}]\n${h.item.text}`
          ).join('\n\n'),
        };
      },
    },
    {
      name: 'get_time',
      description: '获取当前日期时间',
      parameters: Type.Object({}),
      execute: async () => ({
        content: [{ type: 'text' as const, text: new Date().toLocaleString('zh-CN') }],
      }),
    },
  ];

  const agent = new Agent({
    initialState: {
      systemPrompt:
        systemPrompt ??
        '你是一位温柔耐心的学习助教「喵喵老师」。回答要清晰有条理, 适当使用 Markdown 格式, 引用知识库时标注来源。',
      model,
      thinkingLevel: 'low',
      tools,
      messages: [],
    },
    // 将 AgentMessage 转换为 LLM 消息
    convertToLlm: (messages) => messages.map((m) => ({
      role: m.role,
      content: m.content,
    })) as never,
    streamFn: async (model, ctx, opts) => {
      // 复用 llm.ts 的流式实现
      const { streamChat } = await import('./llm');
      return streamChat(
        (ctx as unknown as { messages: Array<{ role: string; content: string }> }).messages,
        { model: model.id }
      ) as never;
    },
  });

  return agent;
}
