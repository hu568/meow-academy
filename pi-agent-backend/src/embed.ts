import { config } from './config';

/**
 * Embedding 服务 (硅基流动 SiliconFlow, OpenAI 兼容 /v1/embeddings)
 * 与 Cherry Studio / rikkahubx 知识库同款方案: BAAI/bge-m3 免费模型
 */
export async function embed(texts: string[]): Promise<number[][]> {
  const key = config.embedding.apiKey;
  if (!key) {
    throw new Error('未配置 SILICONFLOW_API_KEY, 无法生成向量');
  }
  const res = await fetch(`${config.embedding.baseUrl}/embeddings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${key}`,
    },
    body: JSON.stringify({
      model: config.embedding.model,
      input: texts,
      encoding_format: 'float',
    }),
  });
  if (!res.ok) {
    throw new Error(`Embedding API 错误 ${res.status}: ${await res.text()}`);
  }
  const data = (await res.json()) as { data: Array<{ embedding: number[] }> };
  return data.data.map((d) => d.embedding);
}

/** 单条文本向量化 */
export async function embedOne(text: string): Promise<number[]> {
  const [v] = await embed([text]);
  return v;
}
