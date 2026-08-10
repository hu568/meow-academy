# 🐈 RikkaHub 移动端 Agent 框架参考

> 喵学堂安卓端设计蓝本 | 整理：樱茈 | 2026-08-10
> 参考源码: `github.com/rikkahub/rikkahub` (本体) + `wanxiaoT/rikkahubx` (带知识库分支) + `ExTV/rikkahub-agent`

---

## 一、技术栈总览

| 层 | 技术 |
|---|---|
| 语言 | **Kotlin** |
| UI | **Jetpack Compose** + Material You + 深色模式 |
| 持久化 | **Room (SQLite)** + DataStore |
| 依赖注入 | Hilt / Koin |
| 网络 | OkHttp + SSE 流式 |
| Markdown | 自研渲染（代码高亮/LaTeX/表格/Mermaid） |
| Agent | proot Linux 工作区 + 工具系统 + MCP |
| 后台 | WorkManager / AlarmManager |

## 二、分层架构（喵学堂照搬）

```
ui/                        # UI 层
  pages/                   # 页面 (Compose)
    knowledge/             # 知识库页面
      KnowledgePage.kt     # 知识库列表
      KnowledgeDetailPage.kt # 知识库详情(文档列表)
      KnowledgeVM.kt       # 视图模型
service/                   # 服务层
  KnowledgeService.kt      # 知识库核心服务 ⭐
data/                      # 数据层
  db/dao/                  # Room DAO
    KnowledgeBaseDAO.kt
    KnowledgeItemDAO.kt
    KnowledgeChunkDAO.kt
  repository/
    KnowledgeRepository.kt
  model/
    Knowledge.kt           # 数据模型
  knowledge/
    loader/DocumentLoader.kt  # 文档解析
    chunker/TextChunker.kt    # 文本分块 ⭐
    VectorUtils.kt            # 余弦相似度 ⭐
```

## 三、知识库实现核心（rikkahubx 分支）

### 1. 数据模型 (Knowledge.kt)

```kotlin
// 知识库: 每个库有自己的 embedding 模型 + 检索阈值
data class KnowledgeBase(
    val id: Uuid,
    val name: String,
    val embeddingModelId: String,   // 如 BAAI/bge-large-zh-v1.5
    val threshold: Float = 0.3f,    // 相似度阈值过滤
    val topK: Int = 5,
    ...
)

// 条目: 一个文件/笔记/URL
data class KnowledgeItem(
    val id: Uuid,
    val baseId: Uuid,
    val type: KnowledgeItemType,    // FILE / NOTE / URL
    val name: String,
    val filePath: String?,
    val status: ProcessingStatus,   // PENDING / PROCESSING / COMPLETED / ERROR
    val errorMessage: String?,
    ...
)

// 分块: 文本 + 向量(JSON序列化存Room)
data class KnowledgeChunk(
    val id: Uuid,
    val itemId: Uuid,
    val baseId: Uuid,
    val content: String,            // 片段文本
    val embedding: FloatArray,      // 向量 (Room存JSON)
    val metadata: Map<String, String>?,
    val chunkIndex: Int,
)
```

### 2. 分块器 (TextChunker.kt)

```kotlin
object TextChunker {
    const val chunkSize = 500  // 可调, Cherry 用 ~300

    // 单块切分: 超长时按优先级找断点
    private fun findBreakPoint(chunk: String): String {
        // 1. 段落边界 (\n\n)
        // 2. 句子边界 。.!！?？;；
        // 3. 行边界 \n
        // 4. 词边界 (空格)
        // 5. 硬切
    }

    // 先按段落(\n{2,})切, 段落过长再单独细分
    fun chunkByParagraphs(text: String): List<String> {
        val paragraphs = text.split(Regex("\n{2,}"))
        // 段落 ≤ chunkSize → 合并进当前块
        // 段落 > chunkSize → 单独 chunk() 细分
    }
}
```

### 3. 向量工具 (VectorUtils.kt)

```kotlin
object VectorUtils {
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float
    fun dotProduct(a: FloatArray, b: FloatArray): Float
    fun euclideanDistance(a: FloatArray, b: FloatArray): Float
    fun normalize(vector: FloatArray): FloatArray
}
// 注: 向量以 FloatArray 存 Room (JSON序列化), 暴力遍历计算
```

### 4. 检索流程 (KnowledgeService.search)

```kotlin
suspend fun search(baseId, query, topK): List<KnowledgeSearchResult> {
    // 1. 问题向量化 (用该知识库配置的 embeddingModelId)
    val queryEmbedding = generateEmbedding(query, base.embeddingModelId)
    // 2. 取出该库全部 chunks
    val chunks = repository.getChunksByBaseId(baseId)
    // 3. 逐个算余弦相似度
    return chunks.map { chunk ->
        KnowledgeSearchResult(
            chunk = chunk,
            score = VectorUtils.cosineSimilarity(queryEmbedding, chunk.embedding),
            item = repository.getItemById(chunk.itemId)
        )
    }.filter { it.score >= base.threshold }   // 阈值过滤
     .sortedByDescending { it.score }          // 降序
     .take(topK)                               // 取前K
}

// 多库检索: searchMultiple 合并所有库结果, 再整体排序取topK
// 适合聊天: searchAcrossKnowledgeBases(baseIds, query, limit=5)
```

### 5. Embedding 调用 (KnowledgeService.generateEmbeddings)

```kotlin
// 走 OpenAI 兼容 provider (OpenAI Provider Setting)
// 支持任意 OpenAI 兼容 embedding API (如硅基流动 bge)
private suspend fun generateEmbeddings(provider, texts, model): List<FloatArray> {
    val batchSize = 100   // 每批100条, 避免 API 限制
    return texts.chunked(batchSize).flatMap { batch ->
        openAIProvider.embed(provider, batch, model)
    }
}
```

### 6. Room 存储 (DAO)

```sql
-- knowledge_base 表: id, name, embedding_model_id, threshold, top_k, created_at, updated_at
-- knowledge_item 表: id, base_id, type(FILE/NOTE/URL), name, file_path, status, error_message, created_at, updated_at
-- knowledge_chunk 表: id, item_id, base_id, content, embedding(JSON), metadata, chunk_index
```

## 四、Agent 框架 (rikkahub-agent fork)

### 1. Agent 工作区
- **proot 轻量 Linux**：无需 root，手机内跑完整 Linux 发行版
- 内置终端 (Termux)：手动操作 / AI 自动执行
- 能力：信息收集、文档整理、PPT 制作等轻量任务

### 2. 工具系统 (80+ 设备工具)
- 文件管理、浏览器(内嵌,AI驱动)、SSH、屏幕自动化
- 语音转写、音乐播放、日历、屏幕使用时间
- 所有工具**按需开启**(opt-in)，三层安全保护

### 3. Skill / Playbook
- 拖入一个 **Markdown skill 文件** → AI 获得新技能
- 内置目录：二维码生成、维基查询、钢琴、交互地图等
- 可从 URL 或分享文件添加

### 4. 子代理 (Sub-Agents)
- 长任务分发聚焦子代理（独立上下文，可用更小更便宜模型）
- 可并行运行多个，结果汇总返回
- `/stop` 级联取消所有子代理

### 5. MCP 服务器
- 连接 MCP Server → AI 获得其暴露的工具
- AI 可自主增删改 MCP 连接（需审批门控）

### 6. 外部触发
- 通知读取/摘要/转发（白名单制）
- Tasker / ADB 通过 External Automation Intent API 派发任务

## 五、映射到喵学堂

| rikkahub 特性 | 喵学堂落地 |
|---|---|
| Kotlin + Compose + Material You | ✅ 直接采用 |
| KnowledgeService + DAO + 模型 | ✅ 照搬(精简为 md 优先) |
| TextChunker + VectorUtils | ✅ 照搬 |
| OpenAI 兼容 embedding | ✅ 走后端 `/api/v1/embeddings` (bge-m3) |
| 聊天 UI + Markdown 渲染 | ✅ 采用 |
| proot Agent 工作区 | 🔜 二期(可选) |
| MCP / 子代理 / 80+工具 | 🔜 三期(可选) |
| 多 AI Provider | ✅ 由 pi-ai 后端统一提供(1220模型) |

---

*参考来源: rikkahub/rikkahub、wanxiaoT/rikkahubx、ExTV/rikkahub-agent 源码*
