/**
 * 向量检索 (参考 rikkahubx 知识库: SQLite 存 JSON 向量 + 余弦相似度暴力搜索)
 * 适合中小规模知识库 (<10万块), 大库可换 sqlite-vec 或 HNSW 索引
 */

export interface VectorItem {
  id: number;
  text: string;
  source: string;
  chunkIndex: number;
  vector: number[];
  /** 元数据(JSON) */
  meta?: Record<string, unknown>;
}

/** 余弦相似度 [-1, 1] */
export function cosineSimilarity(a: number[], b: number[]): number {
  if (a.length !== b.length) return 0;
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  if (na === 0 || nb === 0) return 0;
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

/**
 * 暴力余弦检索 top-k
 * @param items 全部向量条目
 * @param query 查询向量
 */
export function searchTopK(
  items: VectorItem[],
  query: number[],
  topK: number
): Array<{ item: VectorItem; score: number }> {
  const scored = items.map((item) => ({
    item,
    score: cosineSimilarity(item.vector, query),
  }));
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, topK);
}
