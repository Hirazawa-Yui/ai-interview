# 04-知识库与 RAG

> 模块文档 · 对应代码基线：master · 更新日期：2026-09-27

## 1. 功能概述
两条链路：
1. **入库**：分片上传（模块 01）拿到 fileKey → 注册入库 → Tika 解析 → **Redis Stream 异步向量化**（字符制切块 500 字符/块、相邻重叠 80 字符 → embedding 1024 维 → pgvector）→ 状态机 PENDING/PROCESSING/COMPLETED/FAILED。
2. **问答**：Query Rewrite（LLM 改写，含多轮历史）→ 动态 topK/minScore 向量检索（kb_id 过滤）→ RAG Prompt → **SSE 流式输出**（120 字探测窗口拒答）；另有 rag-chat 多轮会话管理（最近 10 条历史）。

## 2. 涉及文件
后端：
- `knowledge/controller/KnowledgeBaseController.java` — 6 端点（upload/list/detail/delete/category/revectorize）
- `knowledge/controller/RagChatController.java` — 8 端点（SSE 单次问答 + 会话 CRUD + **会话重命名 T21** + **会话换知识库 T23** + SSE 多轮）
- `knowledge/service/impl/KnowledgeBaseServiceImpl.java` — 入库编排：OSS 下载 → 按后缀分流解析（pdf/doc/docx 走 Tika，其余按 UTF-8 文本）→ MD5 去重 → XADD
- `knowledge/splitter/OverlapTextSplitter.java` — **自研字符制重叠分块器（T19）**：按句边界切 + 贪心装箱到 `chunk.size` + 相邻块重叠 `chunk.overlap` 字符（对齐句首）
- `knowledge/service/impl/KbVectorServiceImpl.java` — OverlapTextSplitter 切分 → 删旧向量（JdbcTemplate `DELETE FROM vector_store WHERE metadata->>'kb_id'=?`）→ 每批 ≤10 嵌入入库；相似度检索（filter 表达式 + fallback 本地过滤）
- `knowledge/service/impl/KbQueryServiceImpl.java` — 问答编排：Rewrite → 动态 topK → 检索 → RAG Prompt → SSE 探测窗口
- `knowledge/service/impl/RagChatServiceImpl.java` — 会话 CRUD + AI 占位消息 + 历史拼接（最近 10 条）
- `knowledge/listener/VectorizeProducer.java` / `VectorizeConsumer.java`
- `knowledge/entity/KnowledgeBase.java`、`RagChatSession.java`（kb_ids 存 JSON 字符串）、`RagChatMessage.java`（role/messageOrder/completed）
- `resources/prompts/kb-query-system.st` / `kb-query-user.st` / `kb-query-rewrite.st`
- 配置：`application.yml` 的 `app.ai.rag.*`（**chunk.size 500 / chunk.overlap 80**、rewrite 开关、max-history-chars 200、长短查询阈值、topK 20/12/8、minScore 0.18/0.28）
- `common/StageWatch.java`（T17）— 请求级分段计时器，被上述两个 Impl 用于打单行耗时日志

前端：`pages/KnowledgePage.vue`（4 Tab：上传/列表/RAG问答/历史会话）、`api/rag-chat.js`（会话 CRUD + SSE `streamChat`；T8 起从页面收敛过来）、`utils/sse.js`（SSE 按事件解析，T20）

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
  - ⚠️ **前端解析必须按事件而不是按行**（T20 修复的坑）：Spring 会把一个分片内部换行拆成**同一事件的多个 `data:` 行**，逐行独立派发会丢换行 → markdown 渲染成一整段。现由 `frontend/src/utils/sse.js` 的 `createSseParser` 处理（详见 modules/06 坑位 7、8）。
- **探测窗口**：LLM 偶发"编造式拒答"（内容不够但输出很长），前 120 字检测到拒答特征就整体替换为固定文案，避免垃圾输出。
- **Query Rewrite 为什么要**：口语化问题与文档学术表达有语义差距，LLM 改写显著提升检索命中（成本 ~200 token）。
- **动态 topK**：短查询关键词少要更多候选（20 条+低阈值），长查询语义丰富要精确（8 条+高阈值）。
- **向量检索 fallback**：Spring AI 的 filter 表达式对 pgvector 兼容性偶发问题，失败降级为无过滤检索+本地过滤。
- **历史注入改写**：多轮时把最近历史（助手回复截断 200 字）拼进 rewrite prompt 解决"它/这个"指代。
- 会话列表排序：isPinned DESC + updatedAt DESC；`getNextOrder` 用 last LIMIT 1。
- **会话重命名（T21）**：`PUT /api/rag-chat/sessions/{id}/title`，`title` 去空格后非空、≤50 字（DB 列 `VARCHAR(200)` 只是硬上限，50 是产品上限，避免超长撞 DB 约束变成 500）。**只 set `session_title` 一列、不碰 `updated_at`**：该字段语义是"最后一次对话时间"且是列表排序键，刷它会让旧会话跳到列表顶部、「更新时间」列显示"刚刚"。因此实现用 `LambdaUpdateWrapper.set(...)` 而**不是 `updateById`**——本仓库没有 `MetaObjectHandler`，`updateById` 会把读到的整行快照（含 updatedAt）写回，与并发 `sendMessage` 抢写，可能把时间改小导致排序倒退。会话不存在时抛 `BusinessException(KNOWLEDGE_BASE_NOT_FOUND, "会话不存在")`，与 `getSessionDetail`/`sendMessage` 一致（会话借用知识库的 6xxx 错误码，是既有瑕疵，未新增 ErrorCode）。
- **会话工具栏按钮语义（T22 定稿）**：无会话时是「创建会话」；有会话时左边按钮显示**会话名**、点击即**重命名当前会话**，右边「新建会话」建新会话并清空当前对话。原「重置」按钮已移除——它只是解除会话绑定（后续提问退回单次问答接口），语义不直观且与"新建会话"混淆。
- **会话自动保存（T23）**：前端 `doChat` 在提问前调 `ensureSession()`——没有会话就用默认名（`N个知识库对话`）自动建一个，**每轮对话都自动落库**，不再需要用户先手动创建（对齐主流对话产品的习惯）。建会话失败则中止提问并提示，**不退化到单次问答接口**（那种回答不落库，反而更让人困惑）。副作用：前端已不再调用 `POST /api/knowledge/query/stream`，该单次问答接口目前只有 Swagger/curl 可达。
- **会话知识库动态绑定（T23）**：前端选择框 `@change` 调 `PUT /api/rag-chat/sessions/{id}/kbs` 落库；后端 `sendMessage` 一直用的是**会话里存的** `kb_ids`（不看请求体），所以不落库的话前端怎么改都不生效。失败时前端把选择框**回滚**到已落库的集合并提示，避免"以为生效了其实没变"。至少保留 1 个知识库。
- **向量切分（T19 起）**：自研 `OverlapTextSplitter(chunk.size=500, chunk.overlap=80)`，**字符制**。规则：① 按句末标点（。！？；!?; 与换行）切原子句，标点随前句；② 超长原子句硬切成 500 片段；③ 贪心装箱到 ≤500 字符；④ 下一块从"上一块尾部累计 ≥80 字符的**整句**处"开始 → **重叠对齐句首，不从半句中间切**；⑤ 文末若剩下的内容全在上一块重叠区里则直接收尾（防重复尾块）。
  - **为什么自研**：Spring AI 2.0.0-M4 的 `TokenTextSplitter` builder 根本没有 overlap 参数（javap 实测），且它的 chunkSize 单位是 **token 不是字符**——旧配置 `withChunkSize(800)` 在中文下实际切出平均 1120 字符/块的巨块（实测 kb2：878 块 / 均值 1120 / 最大 2717），既无重叠又让上层 prompt 臃肿。
  - 单元测试 `OverlapSplitterTest`（9 项）：块长上限、相邻块重叠 ≥ overlap、重叠区不被标点截断、不丢字、短文本单块、超长句硬切、空/空白输入、overlap=0 退化、**无重复尾块**。
- **耗时埋点（T17）**：问答每次请求在 `KbQueryServiceImpl` 打一行 `[RAG耗时]`，格式 `总=<墙钟> | rewrite/search/prompt/llm首字/流首字/生成=<各段增量>` + `hits`(命中块数) `ctxChars`(注入上下文字符数) `topK` `minScore` `kbIds` `q`(前 30 字)。分段含义：`llm首字`=LLM 第一个 token，`流首字`=经 120 字探测窗口后真正发给前端的第一个字（两者之差 = 探测窗攒字耗时），`生成`=首字到结束。空结果短路分支也会打（`hits=0`）。多轮链路的前置段另打一行 `[RAG耗时-多轮前置] … | db=<5 次 DB 往返> history=<取历史>`。前端 `KnowledgePage.vue` 结束时打 `[RAG前端] 首字/总/chunks/重渲染次数`（`renderMarkdown` 计数器）。

## 5. 数据
- 表：`knowledge_bases`、`rag_chat_sessions`、`rag_chat_messages`、`vector_store`（Spring AI 自动建，HNSW/COSINE/1024 维，metadata 带 `kb_id`）
- Stream：`knowledgebase:vectorize:stream` / group `vectorize-group`
- 配置键见 §2

## 6. 已知坑位
- ~~**前端 XSS**~~：✅ 已修（T8，2026-09-08）——AI 回复渲染改走 `utils/markdown.js`（marked.parse → DOMPurify.sanitize），注入文本（`<script>`/`<img onerror>`/`[x](javascript:)`）不执行。
- ~~**前端裸 fetch**~~：✅ 已修（T8，2026-09-08）——RAG/会话 5 接口收敛至 `api/rag-chat.js`（会话 CRUD 走 axios 拦截器；SSE `streamChat` 在 api 层封装：resp.ok 检查取业务消息、onData 增量回调、AbortSignal 中断），页面层零裸 fetch；非 2xx 现显示【查询失败：业务消息】而非静默。
- **（观察，2026-09-08 验收时发现，非 T8 引入）向量数据完整性**：vector_store 中 kb1 仅 2 行、kb2 878 行完整。kb1（RocketMQ 书）向量疑似历史缺失；kb2（算法书）中文问题（"快速排序"等）检索 0 命中拒答——疑似英文 chunk 与中文 query 跨语言低分被 minScore 过滤。真实 LLM 逐字流式当前无法在既有文档复现，**用户需上传有效中文文档复核前端流式渲染链路**。
  - **（2026-09-22 更新）该批数据已按用户要求全部清除**（kb1/kb2/kb5 + 3 个会话，四张表归零），上述观察不再可复现；跨语言检索问题待新中文文档上传后重新评估。
- ~~删除知识库不删向量~~：✅ 已修（B4，2026-09-08）——`delete()` 现同步调 `vectorService.deleteByKbId(id)` 清理 vector_store（同库同事务原子）；实测删 kb → vector_store 零残留。`deleteByKbId` 吞异常 warn 语义保留（best-effort）。
- ~~删除知识库不删 `file_info` 秒传登记~~：✅ 已修（T25，2026-09-22）——`delete()` 现在三步清理：OSS 对象（best-effort）→ vector_store → `file_info`（`chunkService.deleteByStorageKey(storageKey)`，同事务、不吞异常）。不清理的后果：同一文件再上传时秒传命中一个已删除的 OSS URL，入库下载失败。详见 modules/01 §6（含实测记录）。
- kb_ids 以 JSON 字符串存储，`parseKbIds` 失败返回空列表（静默）。
- ~~上传事务内 XADD 竞态~~：✅ 已修（T15，2026-09-08）——`upload()` 向量化任务发送改 `TransactionSupport.afterCommit`（同 modules/03 简历与 docs/00 坑 #8）。
- 会话历史消息 `LIMIT 11` 取 11 条过滤当前 user 消息后取 10 条——对消息数边界敏感，改动需小心。
- ~~**RAG 慢的主因不是检索，是模型在"思考"**~~：✅ 已修（T18，2026-09-22）——`qwen3.7-flash` 默认走思考模式（每次调用先产出完整 `reasoning_content` 思维链），T17 埋点实测：总 32.0s/47.7s 里 rewrite 10.1s/19.2s、llm首字 19.5s/23.3s，而 embedding+检索只有 0.2–0.6s。修复 = `LlmConfig` 注入 `extraBody(enable_thinking=false)`，开关 `app.ai.llm.enable-thinking`（默认 false）。**修复后同一问题 7.8s / 5.8s（4–8 倍）**：rewrite 0.46s/0.67s、llm首字 2.4s/0.59s，答案反而更完整（响应体 1125B → 2122B）。属跨模块修复（出题/评估/简历分析走同一 ChatClient 一并受益，出题冒烟 6.0s/5 题正常），详见 docs/00 坑位 #10。
- **（T18/T19 后的新瓶颈，仍未优化）**：`生成` 是绝对大头且**波动大**——同一问题（缓存穿透，topK 12）连跑三次：答案 4570/2861/2920 字节，生成 8461/5026/4455 ms，**约 1.7–1.85 ms / 输出字节**。次之 `流首字` 0.8s（120 字探测窗口的攒字等待，**降到 60 字可省一半 —— 未做**）> `llm首字` 0.4–1.0s > `search` 0.2s。另：同一答案被切成 **100+ 个 SSE 分片**，前端已按 80ms 节流重解析（T20 已修），可用 `[RAG前端] 重渲染=` 对比验证。
  - **结论：调分块/检索只能改善"上下文瘦身 + 首字"，改不动总时长**——总时长由答案长度决定，属于模型侧。
- **（T19 实测，2026-09-22）分块改造的效果边界**：kb5 按 500/80 重建后，`ctxChars` 11283 → 5793（−49%）、`llm首字` 2442ms → 495ms，但端到端总时长被"生成"波动淹没（6.3–7.1s vs 旧的单样本 7.8s）。**分块的收益是检索精度与上下文体积，不要拿它当提速手段讲。**
- **（T17 附带发现）`topK` 按"改写后"长度选取**（`KbQueryServiceImpl:75` 用的是 `rewritten`），把"≤4 字 → topK 20"的设计意图架空了：实测 `缓存穿透`(4 字) 被改写为 44 字后落到 topK 8，与"短查询多取候选"的初衷相反。
- **（T17 附带发现）改写经常白跑**：实测 `Redis 的持久化方式 RDB 和 AOF 有什么区别？`（23 字，自带完整上下文）的改写结果与原文一字不差，却仍花掉 19.2s。`KbQueryServiceImpl:118` 对 >200 字的改写结果也是直接丢弃（白跑一趟）。

## 7. 验证要点
1. 前端知识库 Tab 上传 PDF → 列表状态 PENDING→COMPLETED，chunkCount>0
2. Swagger `POST /api/knowledge/query/stream`（curl -N 看 SSE 逐字输出）→ 问题命中文档内容
3. 问文档外的问题 → 返回"未检索到相关信息"固定文案
4. 创建会话（弹框可命名，留空用默认）→ 多轮追问（用"它"指代上文）→ 验证 rewrite 日志与回答连续性 → 历史会话恢复
5. 会话重命名（T21 回归点）：列表点「重命名」→ 标题即时变化，且**该行在列表中的位置不变、「更新时间」列的值也不变**；空标题/超 50 字给业务提示而非 500
6. 删除文档 → **`file_info` 里该文件的秒传登记应同时消失**（T25）→ 重新上传同名文件 → `check` 返回 `completed:false`（不再秒传）→ 走完整上传后正常入库
7. 每次问答看后端控制台 `[RAG耗时]` 一行（各段耗时 + hits/ctxChars）与浏览器 Console `[RAG前端]` 一行（首字/总时长/重渲染次数）——这是性能回归的判断依据
