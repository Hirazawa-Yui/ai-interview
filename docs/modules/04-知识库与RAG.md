# 04-知识库与 RAG

> 模块文档 · 对应代码基线：master · 更新日期：2026-09-07

## 1. 功能概述
两条链路：
1. **入库**：分片上传（模块 01）拿到 fileKey → 注册入库 → Tika 解析 → **Redis Stream 异步向量化**（切块 800 字符 → embedding 1024 维 → pgvector）→ 状态机 PENDING/PROCESSING/COMPLETED/FAILED。
2. **问答**：Query Rewrite（LLM 改写，含多轮历史）→ 动态 topK/minScore 向量检索（kb_id 过滤）→ RAG Prompt → **SSE 流式输出**（120 字探测窗口拒答）；另有 rag-chat 多轮会话管理（最近 10 条历史）。

## 2. 涉及文件
后端：
- `knowledge/controller/KnowledgeBaseController.java` — 7 端点（upload/list/detail/delete/category/revectorize）
- `knowledge/controller/RagChatController.java` — 6 端点（SSE 单次问答 + 会话 CRUD + SSE 多轮）
- `knowledge/service/impl/KnowledgeBaseServiceImpl.java` — 入库编排：OSS 下载 → 按后缀分流解析（pdf/doc/docx 走 Tika，其余按 UTF-8 文本）→ MD5 去重 → XADD
- `knowledge/service/impl/KbVectorServiceImpl.java` — TokenTextSplitter(800) 切分 → 删旧向量（JdbcTemplate `DELETE FROM vector_store WHERE metadata->>'kb_id'=?`）→ 每批 ≤10 嵌入入库；相似度检索（filter 表达式 + fallback 本地过滤）
- `knowledge/service/impl/KbQueryServiceImpl.java` — 问答编排：Rewrite → 动态 topK → 检索 → RAG Prompt → SSE 探测窗口
- `knowledge/service/impl/RagChatServiceImpl.java` — 会话 CRUD + AI 占位消息 + 历史拼接（最近 10 条）
- `knowledge/listener/VectorizeProducer.java` / `VectorizeConsumer.java`
- `knowledge/entity/KnowledgeBase.java`、`RagChatSession.java`（kb_ids 存 JSON 字符串）、`RagChatMessage.java`（role/messageOrder/completed）
- `resources/prompts/kb-query-system.st` / `kb-query-user.st` / `kb-query-rewrite.st`
- 配置：`application.yml` 的 `app.ai.rag.*`（rewrite 开关、max-history-chars 200、长短查询阈值、topK 20/12/8、minScore 0.18/0.28）

前端：`pages/KnowledgePage.vue`（4 Tab：上传/列表/RAG问答/历史会话）、`api/knowledge.js`（RAG/会话接口在页面内裸 fetch，未收敛）

## 3. 核心流程
```
【入库】ChunkUploader 上传成功 → 前端从 url 提取 fileKey（pathname 去首斜杠）
  → POST /api/knowledge/upload {fileKey, kbName, category}
  → OSS 下载 → 解析文本（二进制格式走 Tika，否则 UTF-8 清洗）→ MD5 去重
  → 入库 vectorStatus=PENDING → XADD knowledgebase:vectorize:stream
  → VectorizeConsumer：切分 → 删旧向量 → 分批 embedding → chunkCount 回写 → COMPLETED

【问答】POST /api/knowledge/query/stream {knowledgeBaseIds, question}（SSE）
  → Query Rewrite（≤200 字才采纳；失败用原问题）
  → topK/minScore 按改写后长度动态选择（≤4字→20条/0.18，5~15字→12条/0.28，≥16字→8条/0.28）
  → similaritySearch（kb_id in [...] 过滤；filter 表达式失败 → 无过滤检索 topK*3 后本地过滤）
  → 检索为空 → 直接返回固定拒答文案
  → RAG Prompt（检索内容包裹动态分隔符）→ ChatClient.stream() → SSE
  → 探测窗口：前 120 字若含"没有找到相关信息"等拒答特征 → 截断为固定拒答文案

【多轮】POST /api/rag-chat/sessions/{id}/messages/stream
  → 存 user 消息 + AI 占位消息（completed=false）→ 历史 10 条拼入 → 复用 answerQuestionStream
  → 流式累积，doOnComplete 回写 AI 消息 content + completed=true
```

## 4. 实现细节
- **SSE 返回格式**：`Flux<String>` 直接 produce `text/event-stream`，前端 fetch 手动解析 `data:` 行；无 Result 包装（流式不走统一响应体）。
- **探测窗口**：LLM 偶发"编造式拒答"（内容不够但输出很长），前 120 字检测到拒答特征就整体替换为固定文案，避免垃圾输出。
- **Query Rewrite 为什么要**：口语化问题与文档学术表达有语义差距，LLM 改写显著提升检索命中（成本 ~200 token）。
- **动态 topK**：短查询关键词少要更多候选（20 条+低阈值），长查询语义丰富要精确（8 条+高阈值）。
- **向量检索 fallback**：Spring AI 的 filter 表达式对 pgvector 兼容性偶发问题，失败降级为无过滤检索+本地过滤。
- **历史注入改写**：多轮时把最近历史（助手回复截断 200 字）拼进 rewrite prompt 解决"它/这个"指代。
- 会话列表排序：isPinned DESC + updatedAt DESC；`getNextOrder` 用 last LIMIT 1。
- **向量切分**：`TokenTextSplitter.withChunkSize(800)`（注意：代码中没有 100 重叠参数，PLAN 里的描述未落地）。

## 5. 数据
- 表：`knowledge_bases`、`rag_chat_sessions`、`rag_chat_messages`、`vector_store`（Spring AI 自动建，HNSW/COSINE/1024 维，metadata 带 `kb_id`）
- Stream：`knowledgebase:vectorize:stream` / group `vectorize-group`
- 配置键见 §2

## 6. 已知坑位
- **前端 XSS**：AI 回复经 `marked` 渲染后直接 `v-html`，无 DOMPurify 消毒（AI 输出不可控场景有注入风险）。
- **前端裸 fetch**：RAG/会话 5 个接口散在 KnowledgePage.vue 内，不走 axios 拦截器（无统一错误提示）。
- ~~删除知识库不删向量~~：✅ 已修（B4，2026-09-08）——`delete()` 现同步调 `vectorService.deleteByKbId(id)` 清理 vector_store（同库同事务原子）；实测删 kb → vector_store 零残留。`deleteByKbId` 吞异常 warn 语义保留（best-effort）。
- kb_ids 以 JSON 字符串存储，`parseKbIds` 失败返回空列表（静默）。
- 会话历史消息 `LIMIT 11` 取 11 条过滤当前 user 消息后取 10 条——对消息数边界敏感，改动需小心。

## 7. 验证要点
1. 前端知识库 Tab 上传 PDF → 列表状态 PENDING→COMPLETED，chunkCount>0
2. Swagger `POST /api/knowledge/query/stream`（curl -N 看 SSE 逐字输出）→ 问题命中文档内容
3. 问文档外的问题 → 返回"未检索到相关信息"固定文案
4. 创建会话 → 多轮追问（用"它"指代上文）→ 验证 rewrite 日志与回答连续性 → 历史会话恢复
5. 删除文档 → 重新上传同名文件 → 正常入库（MD5 不同则重建）
