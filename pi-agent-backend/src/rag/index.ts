import { chunkText, mdToText, type Chunk } from './chunker';
import { searchTopK, type VectorItem } from './retriever';
import { embed, embedOne } from '../embed';
import { config } from '../config';

/**
 * RAG 知识库服务 (内存版, 参考 Cherry Studio / rikkahubx 流程)
 *
 * 写入: md 文件 → 递归分块(300字) → embedding 向量化 → 存储
 * 检索: 问题 → 向量化 → 余弦相似度 top-k → 返回片段
 *
 * 安卓端可复用同样的流程, 将向量存 Room/SQLite (JSON 序列化)
 */
export class RagStore {
  private items: VectorItem[] = [];
  private nextId = 1;

  get size() {
    return this.items.length;
  }

  /** 添加 md 文档: 分块 + 向量化 */
  async addMarkdown(md: string, source: string): Promise<Chunk[]> {
    const text = mdToText(md);
    const chunks = chunkText(text, source);
    const vectors = await embed(chunks.map((c) => c.text));
    for (let i = 0; i < chunks.length; i++) {
      this.items.push({
        id: this.nextId++,
        text: chunks[i].text,
        source: chunks[i].source,
        chunkIndex: chunks[i].index,
        vector: vectors[i],
      });
    }
    return chunks;
  }

  /** 添加纯文本片段 */
  async addText(text: string, source: string, meta?: Record<string, unknown>) {
    const vector = await embedOne(text);
    this.items.push({ id: this.nextId++, text, source, chunkIndex: 0, vector, meta });
  }

  /** 删除某来源的所有片段 */
  removeSource(source: string) {
    this.items = this.items.filter((i) => i.source !== source);
  }

  /** 清空 */
  clear() {
    this.items = [];
    this.nextId = 1;
  }

  /** 检索 top-k 片段 */
  async retrieve(question: string, topK = config.rag.topK) {
    const qv = await embedOne(question);
    return searchTopK(this.items, qv, topK);
  }

  /** 导出全部条目(供持久化) */
  exportItems() {
    return this.items.map(({ vector, ...rest }) => ({ ...rest, vector }));
  }

  /** 导入条目(持久化恢复) */
  importItems(items: Array<Omit<VectorItem, 'vector'> & { vector: number[] }>) {
    this.items = items.map((i) => ({ ...i, id: i.id ?? this.nextId++ }));
    this.nextId = Math.max(...this.items.map((i) => i.id), 0) + 1;
  }
}

/** 全局 RAG 知识库单例 */
export const ragStore = new RagStore();
