import Fastify, { type FastifyInstance } from 'fastify';
import { config } from './config';
import { completeChat, isChatReady, listModels, streamChat } from './llm';
import { embed } from './embed';
import { ragStore } from './rag';
import { createStudyAgent } from './agent';

/** 构造 Fastify 服务并注册全部路由 */
export function buildServer(): FastifyInstance {
  const app = Fastify({ logger: true });

  // ---- 健康检查 ----
  app.get('/health', async () => ({
    ok: true,
    name: 'meow-academy-backend',
    ragChunks: ragStore.size,
    chatReady: await isChatReady(),
  }));

  // ---- 模型列表 ----
  app.get('/api/v1/models', async () => ({
    models: await listModels(),
  }));

  // ---- Embedding (OpenAI 兼容) ----
  app.post('/api/v1/embeddings', async (req, reply) => {
    const body = req.body as { input: string | string[] };
    const inputs = Array.isArray(body?.input) ? body.input : [body?.input];
    try {
      const data = await embed(inputs);
      return {
        object: 'list',
        data: data.map((embedding, i) => ({
          object: 'embedding',
          index: i,
          embedding,
        })),
        model: config.embedding.model,
      };
    } catch (e) {
      reply.code(500);
      return { error: (e as Error).message };
    }
  });

  // ---- 流式对话 (SSE) ----
  app.post('/api/v1/chat', async (req, reply) => {
    const body = req.body as { messages: Array<{ role: string; content: string }>; model?: string; reasoning?: string };
    if (!body?.messages?.length) {
      reply.code(400);
      return { error: 'messages 不能为空' };
    }
    reply.raw.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    const send = (data: unknown) => reply.raw.write(`data: ${JSON.stringify(data)}\n\n`);

    try {
      const stream = streamChat(body.messages, { model: body.model, reasoning: body.reasoning });
      for await (const ev of stream) {
        switch (ev.type) {
          case 'text_delta':
            send({ type: 'delta', content: ev.delta });
            break;
          case 'thinking_delta':
            send({ type: 'thinking', content: ev.delta });
            break;
          case 'done':
            send({ type: 'done', stopReason: ev.stopReason });
            break;
        }
      }
    } catch (e) {
      send({ type: 'error', message: (e as Error).message });
    }
    reply.raw.end();
  });

  // ---- 非流式对话 ----
  app.post('/api/v1/chat/complete', async (req, reply) => {
    const body = req.body as { messages: Array<{ role: string; content: string }> };
    try {
      const { text } = await completeChat(body?.messages ?? []);
      return { content: text };
    } catch (e) {
      reply.code(500);
      return { error: (e as Error).message };
    }
  });

  // ---- RAG 知识库管理 ----
  app.post('/api/v1/rag/documents', async (req, reply) => {
    const body = req.body as { markdown: string; source: string };
    if (!body?.markdown) {
      reply.code(400);
      return { error: 'markdown 不能为空' };
    }
    try {
      const chunks = await ragStore.addMarkdown(body.markdown, body.source ?? 'untitled');
      return { ok: true, chunks: chunks.length, source: body.source };
    } catch (e) {
      reply.code(500);
      return { error: (e as Error).message };
    }
  });

  app.get('/api/v1/rag/stats', async () => ({
    chunks: ragStore.size,
  }));

  app.delete('/api/v1/rag/source/:source', async (req) => {
    const { source } = req.params as { source: string };
    ragStore.removeSource(source);
    return { ok: true, removed: source };
  });

  app.delete('/api/v1/rag', async () => {
    ragStore.clear();
    return { ok: true };
  });

  // ---- RAG 检索 ----
  app.post('/api/v1/rag/search', async (req, reply) => {
    const body = req.body as { query: string; topK?: number };
    if (!body?.query) {
      reply.code(400);
      return { error: 'query 不能为空' };
    }
    try {
      const hits = await ragStore.retrieve(body.query, body.topK);
      return {
        results: hits.map((h) => ({
          text: h.item.text,
          source: h.item.source,
          chunkIndex: h.item.chunkIndex,
          score: Number(h.score.toFixed(4)),
        })),
      };
    } catch (e) {
      reply.code(500);
      return { error: (e as Error).message };
    }
  });

  // ---- Pi Agent 智能体对话 (带工具: RAG检索/时间) ----
  app.post('/api/v1/agent/chat', async (req, reply) => {
    const body = req.body as { message: string; systemPrompt?: string; stream?: boolean };
    if (!body?.message) {
      reply.code(400);
      return { error: 'message 不能为空' };
    }
    try {
      const agent = await createStudyAgent(body.systemPrompt);
      const result = await agent.prompt(body.message);
      const text = result.messages
        .filter((m) => m.role === 'assistant')
        .flatMap((m) => m.content ?? [])
        .filter((c) => typeof c === 'object' && c !== null && 'text' in c)
        .map((c) => (c as { text: string }).text)
        .join('');
      return { content: text || '喵~(没有生成内容)' };
    } catch (e) {
      reply.code(500);
      return { error: (e as Error).message };
    }
  });

  return app;
}
