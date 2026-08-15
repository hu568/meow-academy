# RAG 算法参考（归档自 pi-agent-backend）

> 来源：原 `pi-agent-backend/src/rag/`（chunker.ts / retriever.ts）+ `src/embed.ts`。
> 归档原因：pi-agent-backend 已弃用删除，但 RAG 算法（分块 / 余弦检索 / SiliconFlow bge-m3 向量化）是 M4/M5 实现 DSH RAG 插件或 Kotlin 端 RAG 的参考，故保留在此。
> 归档日期：2026-08-15。

## 一、整体流程

```
md 文件
  │ 1. mdToText（去代码块标记、链接、图片、标题符号 → 纯文本）
  ▼
  │ 2. chunkText（递归分块，~300 字/块，50 字重叠）
  ▼
  │ 3. embed（POST SiliconFlow /v1/embeddings，bge-m3，1024 维 float）
  ▼
向量化分块 → 存储
  │ 4. 检索：问题向量化 → 余弦相似度暴力搜索 → top-k（默认 5）
  ▼
拼接提示词（片段 + 来源标注）→ LLM
```

## 二、分块器（chunker.ts）

### 参数

| 参数 | 值 |
|---|---|
| chunkSize | 300（字符） |
| chunkOverlap | 50（字符） |
| topK | 5 |

### 分隔符层级（递归分割，从粗到细）

```
'\n## '（二级标题）→ '\n# '（一级标题）→ '\n\n'（段落）→ '\n'（行）
→ '。' → '！' → '？' → '；' → '，' → ' ' → ''（硬切）
```

### 算法要点

1. 递归分割：当前内容长度 ≤ chunkSize 或分隔符耗尽时，trim 后作为一个块。
2. 按分隔符切分后，逐段累积到 ~chunkSize，超出则把上一段递归细分。
3. 后处理合并：相邻小块合并到 ~chunkSize + chunkOverlap（带重叠效果），块 index 重新编号。
4. 每块结构：`{ text, source, index }`。

### mdToText 清洗规则

- 代码块 `\`\`...\`\`\` → `[代码块] ... [/代码块]`
- 图片 `![alt](url)` → `[图片]`
- 链接 `[text](url)` → `text`
- 标题/加粗/引用/表格符号 `#>*`~|-` → 空格
- 多余空白 → 单空格

## 三、向量化（embed.ts，SiliconFlow）

| 项 | 值 |
|---|---|
| 服务 | SiliconFlow（OpenAI 兼容 /v1/embeddings） |
| 模型 | BAAI/bge-m3 |
| 维度 | 1024 |
| encoding_format | float |
| 端点 | `https://api.siliconflow.cn/v1/embeddings` |
| 鉴权 | `Authorization: Bearer <SILICONFLOW_API_KEY>` |

请求体：`{ model, input: string[], encoding_format: 'float' }`
响应：`{ data: [{ embedding: number[] }] }`（按输入顺序）

## 四、检索器（retriever.ts，余弦 top-k）

### 数据结构

```ts
interface VectorItem {
  id: number
  text: string
  source: string
  chunkIndex: number
  vector: number[]      // 1024 维
  meta?: Record<string, unknown>
}
```

### 余弦相似度

```ts
function cosineSimilarity(a: number[], b: number[]): number {
  if (a.length !== b.length) return 0
  let dot = 0, na = 0, nb = 0
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i]
    na += a[i] * a[i]
    nb += b[i] * b[i]
  }
  if (na === 0 || nb === 0) return 0
  return dot / (Math.sqrt(na) * Math.sqrt(nb))   // [-1, 1]
}
```

### 检索

暴力全量计算余弦相似度 → 降序排序 → 取 top-k。适合中小规模（<10 万块），大库可换 sqlite-vec / HNSW。

## 五、RAG 存储（rag/index.ts，内存版）

- `addMarkdown(md, source)`：mdToText → chunkText → embed（批量）→ 存入 items。
- `addText(text, source, meta)`：单条向量化存入。
- `removeSource(source)` / `clear()` / `retrieve(question, topK)` / `exportItems()` / `importItems()`。
- 内存版无持久化；Android 端可复用同样流程，把向量存 Room/SQLite（JSON 序列化）。

## 六、M4/M5 复用指引

- DSH RAG 插件（自定义 Cordis 插件，仿 meow-extensions）：把 `rag_search` 做成模型工具，内部用上面的分块 + 检索 + SiliconFlow 向量化。
- Kotlin 端 RAG：分块逻辑（递归分块 300/50）与余弦检索可在 Kotlin 复刻，向量化仍调 SiliconFlow API。
- 关键参数统一：chunkSize=300 / chunkOverlap=50 / topK=5 / bge-m3 / 1024 维。
