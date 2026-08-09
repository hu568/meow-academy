import 'dotenv/config';

/**
 * 喵学堂后端配置
 * 所有配置均可通过环境变量覆盖 (.env 文件)
 */
export const config = {
  /** HTTP 服务端口 */
  port: Number(process.env.PORT ?? 8787),

  /** 聊天默认模型 (provider, model) */
  chatModel: {
    provider: process.env.CHAT_PROVIDER ?? 'deepseek',
    model: process.env.CHAT_MODEL ?? 'deepseek-chat',
  },

  /** Embedding (硅基流动 bge-m3, 免费) */
  embedding: {
    apiKey: process.env.SILICONFLOW_API_KEY ?? '',
    model: process.env.EMBEDDING_MODEL ?? 'BAAI/bge-m3',
    baseUrl: process.env.EMBEDDING_BASE_URL ?? 'https://api.siliconflow.cn/v1',
    /** 向量维度 */
    dims: 1024,
  },

  /** RAG 检索参数 (参考 Cherry Studio) */
  rag: {
    /** 分块大小(字符) */
    chunkSize: 300,
    /** 分块重叠 */
    chunkOverlap: 50,
    /** 检索返回 top-k 片段 */
    topK: 5,
  },
};
