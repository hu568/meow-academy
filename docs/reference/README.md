# 📚 参考实现文档索引

喵仓开发时对照的参考实现，按主题查阅喵：

| 文档 | 内容 | 适用场景 |
|---|---|---|
| [cherry-studio-rag.md](cherry-studio-rag.md) | 🍒 Cherry Studio 知识库实现：分块/向量化/检索/重排序/痛点 | RAG 管道设计 |
| [rikkahub-agent.md](rikkahub-agent.md) | 🐈 RikkaHub 移动端 Agent 框架：分层架构/知识库代码/工具系统 | 安卓端架构 |

## 核心结论速查

- **分块**：段落优先 + 句子边界断句（参考两家的递归分割思路，~300字）
- **向量**：bge-m3 (1024维)，硅基流动免费 API
- **存储**：Room/SQLite 存向量 JSON（rikkahubx 同款），暴力余弦检索够用
- **检索**：余弦相似度 → 阈值过滤 → top-k
- **回答**：检索片段+问题 → pi-ai 网关 → DeepSeek → SSE 流式返回

---
*由樱茈整理，持续更新喵 🐾*
