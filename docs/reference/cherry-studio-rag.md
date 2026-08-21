# 🍒 Cherry Studio 知识库 (RAG) 实现参考

> 喵仓 RAG 设计蓝本 | 整理：樱茈 | 2026-08-10

---

## 一、总体流程

```
新建知识库(选Embedding模型)
      │ 添加文档(PDF/DOCX/TXT/MD/XLSX/网页/目录)
      ▼
文档复制到本地数据目录
      │ 1. 文本提取
      ▼
文本分块 (long chain 递归分割器, 按段落优先, 约300字/块)
      │ 2. Embedding 向量化 (bge-m3 / nomic-embed-text 等)
      ▼
向量 + 原文片段 → 写入本地向量数据库 (默认 turso libSQL)
      │ 3. 提问时
      ▼
问题向量化 → 余弦/欧氏相似度检索 → top-k 片段(带匹配分)
      │ 4. (可选) 重排序模型 rerank 语义重排
      ▼
检索片段 + 问题 → 拼接提示词 → 大模型 → 回答(标注来源)
```

## 二、关键实现细节

### 1. 知识库配置
- 每个知识库需指定一个 **Embedding 模型**：
  - `BAAI/bge-m3`（硅基流动免费，1024维）⭐ 推荐
  - `nomic-embed-text`（本地 Ollama）
  - DeepSeek 官方免费 embedding
- 可配置 **重排序模型**（如硅基流动 rerank）提升精度

### 2. 分块策略（Long Chain 递归分割）
- 优先按 **段落** 分块；段落过长再按固定字数(~300字)切
- 实现要点：递归分割器按分隔符优先级逐级拆分：
  ```
  段落(\n\n) → 标题 → 句子(。！？；) → 行(\n) → 词(空格) → 硬切
  ```
- **已知痛点**：300字硬切会截断句子，AI 理解上下文受限

### 3. 向量化
- 每个文本块调 Embedding API → 变成长向量（bge-m3 = 1024 维）
- 问题也同样向量化

### 4. 向量存储
- 默认 **turso libSQL**（SQLite 分支）本地存储
- 也可通过 MCP Server 存 PostgreSQL 等

### 5. 检索
- 余弦相似度 / 欧氏距离
- 返回最相似片段 + 匹配分数
- 可加 **重排序模型** 对初检结果语义重排（例：搜"曹操兵器"，倚天剑 78% 分排到第二）

### 6. 提示词拼接
- 检索片段（含来源）与问题一起发给大模型
- 大模型负责归纳总结，**回答质量取决于检索精度**

## 三、三大痛点（喵仓要避开的坑）

| 痛点 | 表现 | 对策 |
|---|---|---|
| 分块粗糙 | 300字硬切截断句子 | 段落优先 + 句子边界断句 + 重叠 |
| 检索不精准 | 纯向量匹配，语义理解有限 | 重排序模型 / 阈值过滤 / 多路召回 |
| 缺乏全局视角 | 统计类问题(如"共多少学生")答错 | 结构化数据走 SQLite Wiki 查询，不走向量 |

## 四、映射到喵仓的实现

| Cherry Studio | 喵仓(安卓端) | 喵仓(后端) |
|---|---|---|
| turso libSQL 向量库 | Room/SQLite 存向量 JSON | - |
| long chain 递归分割 | `TextChunker.kt`(参考 rikkahubx) | `src/rag/chunker.ts` |
| bge-m3 embedding | 调后端 `/api/v1/embeddings` | `src/embed.ts` (SiliconFlow) |
| 余弦相似度检索 | `VectorUtils.kt` | `src/rag/retriever.ts` |
| 提示词拼接 | RAG 检索 → `/api/v1/chat` | `src/server.ts` |

---

*参考来源: Cherry Studio 官方文档、社区拆解文章、rikkahubx 知识库实现*
