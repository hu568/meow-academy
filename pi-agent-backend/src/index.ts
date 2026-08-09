import { buildServer } from './server';
import { config } from './config';

/** 喵学堂 Pi Agent 后端入口 */
const app = buildServer();

app.listen({ port: config.port, host: '0.0.0.0' }).then((addr) => {
  console.log(`🐾 喵学堂 Pi Agent 后端已启动: ${addr}`);
  console.log(`   - 健康检查: ${addr}/health`);
  console.log(`   - RAG 检索: POST /api/v1/rag/search`);
  console.log(`   - 流式对话: POST /api/v1/chat (SSE)`);
  console.log(`   - Agent对话: POST /api/v1/agent/chat`);
  console.log(`   - Embedding: POST /api/v1/embeddings`);
});
