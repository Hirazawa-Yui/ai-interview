# AI-Interview 平台重建方案

## Context

用户购买了一个AI模拟面试平台项目，但因为源码过度抽象、知识星球文档质量差、语音面试等模块不熟悉，无法在面试中自信讲解。用户有另一个手写的 flash-sale-platform 项目，因为代码全是自己写的所以很有底气。决定从零 vibe coding 重建，去语音面试，加分片上传/断点续传/RAG等易理解的亮点。

## 技术栈

| 组件 | 选型 | 原因 |
|------|------|------|
| 语言/框架 | Java 21 + Spring Boot 4.x | 跟上最新版本，简历更好看 |
| 构建工具 | Maven（不用Gradle） | 用户熟悉，简单直接 |
| ORM | MyBatis-Plus | 用户熟悉，复杂查询更灵活 |
| 数据库 | PostgreSQL 14+ + pgvector | 向量检索，已有Docker部署 |
| 缓存/消息 | Redis 7.x + Spring Data Redis（不用Redisson） | 轻量，更贴近原生命令 |
| 文件存储 | 阿里云OSS | 用户已有AccessKey，配置即用，面试也能讲清S3协议 |
| AI | 单个LLM配置 + Spring AI ChatClient | 简单，不搞多Provider管理 |
| 文档解析 | Apache Tika（仅PDF/Word/文本） | 轻量引入，不用全量parser |
| 工具库 | Lombok + Hutool | 用户熟悉 |

**基础设施（复用现有Docker Desktop容器，新建逻辑库隔离）：**
- PostgreSQL：现有容器 `postgres-service` (port 5432)，新建数据库 `ai_interview`（旧项目用 `interview_guide`）
- Redis 7：现有容器 `my_redis` (port 6380)，切换至 **1号库**（旧项目用0号库或9号库，互不干扰）
- 在 application.yml 中通过 `spring.data.redis.database: 1` 和 `spring.datasource.url` 中 `ai_interview` 库名来隔离

## 项目结构（单模块Maven，扁平包结构）

```
ai-interview/
├── pom.xml
├── src/main/java/com/aiinterview/
│   ├── Application.java
│   ├── controller/          # FileChunkController, ResumeController, KnowledgeBaseController,
│   │                          KnowledgeBaseChatController, InterviewController, ScheduleController
│   ├── service/             # IXxxService接口 + impl/XxxServiceImpl实现（参照flash-sale-platform风格）
│   │   └── impl/
│   ├── stream/              # Redis Stream生产者+消费者（模板方法模式，用抽象类）
│   │   └── listener/        # Stream消费者（消息监听线程）
│   ├── mapper/              # MyBatis-Plus BaseMapper
│   ├── entity/              # 数据库实体
│   ├── dto/                 # Result + 请求/响应DTO
│   ├── config/              # MyBatisPlusConfig, RedisConfig, OssConfig, LlmConfig, CorsConfig
│   ├── annotation/          # @RateLimit
│   ├── aspect/              # RateLimitAspect, LogAspect
│   ├── filter/              # TokenFilter
│   ├── exception/           # ErrorCode, BusinessException, WebExceptionHandler
│   └── constant/            # CommonConstants, RedisStreamKeys
├── src/main/resources/
│   ├── application.yml
│   ├── scripts/rate_limit.lua
│   └── prompts/             # LLM提示词模板（.st文件）
```

**关键风格约定：** 中文编号步骤注释、Service层用 interface + impl（参照flash-sale-platform）、薄Controller厚Service、统一Result包装

## 模块清单与实现顺序

### Phase 1：项目骨架
- Maven初始化、依赖声明、Application主类
- application.yml配置（PG 5432端口 `ai_interview`库、Redis 6380端口 **1号库**、OSS AccessKey、单个LLM）
- 基础类：Result<T>、ErrorCode、BusinessException、WebExceptionHandler
- 参照flash-sale-platform的目录结构和命名习惯初始化包结构

### Phase 2：文件分片上传（基础能力，其他模块依赖它）

**核心方案：** 前端5MB切片 + Redis状态追踪 + OSS Multipart Upload + 前端触发合并

**与 MinIO 方案的差异：** 存储层用阿里云 OSS 原生 Multipart Upload API（`CreateMultipartUpload` → `UploadPart` → `CompleteMultipartUpload`），替代 MinIO 的 `composeObject`。合并由 OSS 服务端完成，不下载到服务器。

**Redis 状态键设计（所有 Key 24小时 TTL）：**
| Key | 类型 | 说明 |
|-----|------|------|
| `file:chunks:{md5}` | Set | 已上传的分片序号集合 |
| `file:upload:{md5}` | Hash | `{uploadId, totalChunks, fileName, fileSize}` |
| `file:etags:{md5}` | Hash | `{partNumber → eTag}` (OSS 返回的分片校验值) |
| `file:merge:{md5}` | String | 合并锁 (SETNX，防并发合并) |

**API 设计（4个接口）：**

1. **`POST /api/file/check`** — 查询上传状态（秒传/断点续传/新任务）
   - 参数：`{ md5, fileName, fileSize, totalChunks }`
   - 先查 DB `file_info` 表，MD5 已存在 → 返回 `{completed: true, url}` (秒传)
   - 再查 Redis `file:upload:{md5}`，有记录 → 返回 `{completed: false, uploadedChunks: [0,1,2], uploadId}` (断点续传)
   - 无记录 → 调 OSS `CreateMultipartUpload`，存 Redis，返回 `{completed: false, uploadedChunks: [], uploadId, totalChunks}` (新任务)

2. **`POST /api/file/chunk`** — 上传单个分片
   - 参数：`{ md5, chunkIndex, chunkMd5, file }`（multipart/form-data）
   - **先落盘：** 调 OSS `UploadPart(uploadId, partNumber, inputStream)`
   - 成功后后端重算分片 MD5，与前端传来的 `chunkMd5` 比对，不一致 → 拒绝并返回错误要求重传
   - **后记账：** `SADD file:chunks:{md5} chunkIndex` + `HSET file:etags:{md5} chunkIndex eTag`
   - 返回 `{ok: true}`

3. **`POST /api/file/merge`** — 前端确认全部分片上传完毕后调用（只调用一次）
   - 参数：`{ md5, fileName }`
   - **SETNX 合并锁：** `SET file:merge:{md5} 1 NX EX 60`，抢不到锁直接查 DB 返回已有结果
   - **数据库二次校验：** 查 `file_info` 是否已有该 MD5 记录（幂等保护）
   - **校验分片数：** `SCARD file:chunks:{md5}` == totalChunks
   - **OSS 合并：** `CompleteMultipartUpload(uploadId, partETags[])` → OSS 服务端按 partNumber 拼接
   - **MD5 校验：** 下载合并后的文件头尾采样 + 文件大小比对（OSS Complete 后无法直接 GetObject MD5，用 ETag 近似校验）
   - **入库：** `file_info` 表插入记录
   - **清理：** 删除 Redis 中该 MD5 的所有临时 Key
   - 返回 `{url}`

4. **`GET /api/file/preview/{md5}`** — 查询已上传的分片进度（前端暂停/刷新后恢复用）
   - 返回 `{totalChunks, uploadedChunks: [...]}`

**文件清单：**
- `entity/FileInfo.java` + `mapper/FileInfoMapper.java`
- `service/IFileChunkService.java` + `service/impl/FileChunkServiceImpl.java`
- `service/IFileStorageService.java` + `service/impl/FileStorageServiceImpl.java`（封装 OSS 操作，包含 MultipartUpload 方法）
- `controller/FileChunkController.java`
- `dto/ChunkCheckRequest.java`, `ChunkMergeRequest.java`, `ChunkUploadResponse.java`

**Phase 2 为后续模块提供的复用能力：**

| 能力 | 谁用 | 说明 |
|------|------|------|
| `IFileStorageService.uploadFile()` | Phase 4简历、Phase 5知识库 | 简单 putObject，适用于小文件（<10MB） |
| `IFileStorageService.downloadFile()` | Phase 4、5、6 | 从OSS下载文件 |
| `IFileStorageService.getFileUrl()` | Phase 4、5 | 获取OSS访问URL |
| 分片上传 API（/check /chunk /merge） | **仅 Phase 5 知识库** | 大文档（>50MB）走分片，简历不走走分片，简历直接用 `uploadFile()` 简单上传 |

### Phase 3：Prompt 注入防护（三层保护）

**背景：** 平台几乎所有功能都在和大模型交互——简历评估、面试出题、知识库问答、评估报告。用户的简历、上传的文档、面试回答最终都会拼进 Prompt 里。Prompt 本质就是纯文本拼接，没有"指令"和"数据"的天然边界，攻击面特别大。SQL 注入有参数化查询这种成熟手段，但 LLM 的 Prompt 注入目前没有标准化防御方案，只能靠工程手段多层叠加。

#### 第一层：正则净化（纯代码层，不经过 LLM）

所有用户输入在拼入 Prompt 之前先过正则，命中后用占位符替代再交给大模型。

**正则模式（配置化，与代码解耦）：**
- 行首角色标记：`(?im)^\s*(system|user|assistant|human|ai|model)\s*[:：].*` → `[filtered-role-marker]`
- 指令覆盖短语（英文）：`ignore\s+(previous|above|all|your)\s*(instructions|prompts|rules)` 等 → `[filtered]`
- 指令覆盖短语（中文）：`忽略之前的指令|忘记之前的指令|忽略以上所有|你不再是|你的新角色是` → `[filtered]`
- 分隔符伪造：`---(?:简历|文档|问答)内容(?:开始|结束)---` → `[filtered-delimiter]`
- 动态标签伪造：`</?data-boundary[^>]*>` → `[filtered-boundary-tag]`

**设计要点：** 行首角色标记用 `(?im)^` 只匹配行首避免误杀；指令短语匹配完整句式；**只净化不报错**，不给攻击者探测反馈。

#### 第二层：动态分隔符 + 提示词加固

1. 每次调用 LLM 时生成随机 UUID 片段（8位）
2. 用 `<data-boundary-{uuid}-{label}> ... </data-boundary-{uuid}-{label}>` 包裹所有用户输入
3. 在 System Prompt 末尾追加防注入指令，告诉 LLM 标签内是数据不是指令

**为什么用随机 UUID？** 攻击者猜不到分隔符就无法提前闭合标签，每次调用 UUID 不同。

#### 第三层：输出护栏

LLM 输出后检测"投降语"（`I'll now act as`、`我已经忽略`、`新的角色是` 等），命中则返回系统繁忙。这些词在正常面试评估中绝不会出现。

**JSON 结构化输出作为天然兜底：** 所有 LLM 调用要求 JSON 返回，注入导致非 JSON 输出 → 解析失败 → 重试 → 最终抛异常，不会把异常文本返回用户。

**文件清单：**
- `service/IPromptDefenseService.java` → 接口
- `service/impl/PromptDefenseServiceImpl.java` → 三层防护核心实现
- `common/ai/PromptSanitizer.java` → 第一层正则（从原项目改写）
- `common/ai/PromptSecurityConstants.java` → 防注入指令常量（照搬原项目）
- `common/ai/OutputGuardConfig.java` → 第三层敏感词配置
- `application.yml` 新增 `app.ai.defense.*` 配置块

### Phase 4：简历管理 + Redis Stream异步分析

**核心流程：** 上传简历 → Tika解析文本 → OSS存储 → 入库（PENDING）→ Redis Stream → 异步消费者调LLM分析 → 入库结果（COMPLETED/FAILED）→ 前端轮询

#### 数据库表

- `resumes` — id, file_md5, original_filename, file_size, storage_key, storage_url, parsed_text(TEXT), analyze_status(PENDING/PROCESSING/COMPLETED/FAILED), analyze_error, timestamps
- `resume_analyses` — id, resume_id, overall_score(0-100), summary(TEXT), strengths_json(TEXT), suggestions_json(TEXT), ai_raw_response(TEXT), analyzed_at

#### 上传流程（不分片，直接 multipart）

1. 前端直接 POST multipart 文件到 `/api/resumes/upload`（简历通常 < 10MB，一次请求即可）
2. Tika 校验文件类型（PDF/DOCX/TXT）+ 解析文本
3. **调 `FileStorageService.uploadFile()`** — 简单 putObject 到 OSS，返回 storageKey/URL
4. 入库 resumes（parsed_text + storageKey + analyzeStatus=PENDING）
5. XADD `resume:analyze:stream` → 返回 `{resumeId, analyzeStatus}`

> **为什么不分片？** 简历通常只有几MB，一次 multipart 上传就够了。Phase 2 的分片上传（/check /chunk /merge）是给知识库的大文档（50MB+）用的。简历这里只复用 Phase 2 的 `FileStorageService`（OSS 读写），不走分片流程。

#### 异步分析（AnalyzeStreamConsumer，单线程守护）

1. XREADGROUP 阻塞读取 → markProcessing → 构建Prompt（system模板 + Phase 3防注入 + user模板）→ ChatClient调用 → BeanOutputConverter结构化JSON → Phase 3 guardOutput → 入库
2. 异常：retryCount<3 → 重新XADD；>=3 → markFailed；都ACK原消息

#### Redis Stream

| 项 | 值 |
|------|------|
| Stream Key | `resume:analyze:stream` |
| Consumer Group | `analyze-group` |
| 批量/阻塞 | 10条/次, 1000ms |
| 重试 | 最多3次（应用层retryCount字段） |
| 裁剪 | MAXLEN ~ 1000 |

#### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/resumes/upload` | 上传（multipart），返回 `{resumeId, analyzeStatus}` |
| GET | `/api/resumes` | 简历列表（含最新评分） |
| GET | `/api/resumes/{id}/detail` | 详情（含解析文本 + 分析历史） |
| DELETE | `/api/resumes/{id}` | 删除（OSS + 关联分析） |
| POST | `/api/resumes/{id}/reanalyze` | 重新分析 |

#### 文件清单

- `entity/Resume.java`, `entity/ResumeAnalysis.java`
- `mapper/ResumeMapper.java`, `mapper/ResumeAnalysisMapper.java`
- `dto/ResumeUploadResponse.java`, `dto/ResumeDetailDTO.java`, `dto/ResumeListItemDTO.java`, `dto/ResumeAnalysisResponse.java`
- `service/IResumeService.java` + `impl/ResumeServiceImpl.java`
- `service/IResumeParseService.java` + `impl/ResumeParseServiceImpl.java`（Tika封装）
- `service/IResumeAnalysisService.java` + `impl/ResumeAnalysisServiceImpl.java`（LLM评分）
- `stream/AbstractStreamProducer.java`, `stream/AbstractStreamConsumer.java`（Spring Data Redis版）
- `stream/listener/ResumeAnalysisProducer.java`, `stream/listener/ResumeAnalysisConsumer.java`
- `controller/ResumeController.java`
- `resources/prompts/resume-analysis-system.st`, `resources/prompts/resume-analysis-user.st`

### Phase 5：RAG知识库

**核心流程：** 上传文档（复用Phase2分片上传）→ Tika解析 → 入库 → Redis Stream异步向量化 → pgvector存储 → 用户提问 → Query Rewrite → 向量检索 → RAG Prompt → SSE流式回答

#### 数据库表
- `knowledge_bases` — file_md5, kb_name, category, storage_key, parsed_text, vector_status(PENDING/PROCESSING/COMPLETED/FAILED), chunk_count, question_count
- `rag_chat_sessions` — session_title, kb_ids(JSON), is_pinned
- `rag_chat_messages` — session_id, role(user/assistant), content, message_order, completed

#### 上传流程
1. 前端 ChunkUploader → Phase 2 分片上传 → 拿到 fileKey
2. POST `/api/knowledge/upload` {fileKey, kbName, category}
3. Tika解析 → 入库(vectorStatus=PENDING) → XADD Stream
4. VectorizeConsumer：切分(800字/100重叠)→Embedding→PgVectorStore→COMPLETED

#### 问答流程（SSE）
1. Query Rewrite（LLM改写，≤4字短query扩写补全）
2. 动态topK（≤4字→20条, 5~15字→12条, ≥16字→8条）
3. pgvector余弦检索（kb_id过滤+minScore阈值）
4. RAG Prompt（system+Phase3防注入+检索内容+问题）
5. ChatClient.stream() → SSE探测窗口（前120字检测拒答）
6. 更新 question_count

#### API
11个端点：知识库CRUD(7个) + SSE流式问答 + RAG会话管理(4个)

#### 分步实现

**5a：文档管理 + 向量化（先做，~12文件）**
- DTO: KbUploadRequest, KbListItemDTO + Prompt模板3个 + StreamKeys更新
- IKbVectorService: TokenTextSplitter(800字/100重叠) + 批量嵌入 + PgVectorStore + 相似度检索
- IKnowledgeBaseService: 上传(接收fileKey) + CRUD
- VectorizeProducer + VectorizeConsumer（复用AbstractStream）
- KnowledgeBaseController（7端点）
- 验证：编译 + 上传测试

**5b：RAG问答 + 会话 + 前端（后做，~10文件）**
- DTO: KbQueryRequest, KbQueryResponse, 会话/消息DTO
- IKbQueryService: Query Rewrite + 动态topK + RAG Prompt + SSE流式 + 120字探测窗口
- IRagChatService: 会话CRUD + 消息管理 + 多轮对话
- RagChatController（4端点）
- 验证：编译 + SSE流式测试
- 前端: KnowledgePage.vue

### Phase 6：模拟面试
- 创建会话 → AI按技能方向出题 → 缓存到Redis → 用户逐题作答 → 最后异步评估
- 评估模式（借鉴原项目UnifiedEvaluationService）：分批评估(4题/批) → 二次汇总 → 降级到简单拼接
- 结构化输出：BeanOutputConverter + 最多3次重试修复
- API：创建/获取会话、提交答案、获取报告、导出

### Phase 7：面试日程（可选）
- 简单CRUD + AI解析面试邀请文本
- 独立模块，不与其他模块耦合

## 关键架构决策（面试QA素材）

1. **为什么Redis Stream而不是RocketMQ？** — Redis已是必选依赖，复用做MQ减少Docker容器。单机部署消息量小，Redis Stream的Consumer Group + ACK完全够用。未来切换成本低（AbstractStreamConsumer抽象了差异）。

2. **为什么Spring Data Redis而不是Redisson？** — 更贴近原生Redis命令，限流Lua脚本不超过30行，`StringRedisTemplate.execute()`直接执行，无需学Redisson的RScript API。Stream操作用`opsForStream()`也直观。

3. **为什么MyBatis-Plus而不是JPA？** — 面试平台查询模式复杂多变（多维筛选简历、知识库全文搜索）。LambdaQueryWrapper灵活，复杂SQL写XML。pgvector向量检索用JdbcTemplate原生SQL，不依赖ORM。

4. **为什么分片上传？** — 知识库文档可能超50MB，直接上传易因网络抖动失败。5MB分片独立上传，失败只重传该块。MD5秒传避免重复存储和向量化。

5. **为什么Query Rewrite？** — 用户口语化问题和知识库学术表达有语义差距。LLM改写后再检索，命中率显著提升。额外200 tokens成本远低于检索失败后LLM瞎编的成本。

6. **为什么 Prompt 注入要分三层防御？** — 纵深防御思想在 LLM 场景的应用。第一层正则捕已知攻击（快、确定性），第二层随机分隔符+提示词加固挡未知攻击（通用、不可预测），第三层输出护栏兜底（"即使前两层被绕过也不能把坏结果给用户"）。每层用完全不同的机制，三层同时失效的概率极低。类比：正则=参数校验、分隔符=参数化查询、输出护栏=SQL结果的二次校验。

7. **为什么评估要分批+汇总+降级？** — 一场面试可能10+题，一次评估可能超过LLM输出token限制。分批评估(4题/批)互不影响，二次汇总合成最终报告，汇总失败降级到简单拼接，确保任何情况下都有结果返回。

## 参考代码

从原项目中借鉴（改写适配新栈）：
- `D:\MyCode\AgentProjects\interview-guide-backend\...\common\ai\StructuredOutputInvoker.java` → 结构化输出+重试
- `D:\MyCode\AgentProjects\interview-guide-backend\...\common\evaluation\UnifiedEvaluationService.java` → 分批评估模式
- `D:\MyCode\AgentProjects\interview-guide-backend\...\common\async\AbstractStreamConsumer.java` → Stream消费者模板
- `D:\MyCode\AgentProjects\interview-guide-backend\...\modules\knowledgebase\service\KnowledgeBaseQueryService.java` → RAG问答流程

从flash-sale-platform中复用风格：
- `D:\MyCode\idea_projects\flash-sale-platform\...\aop\RateLimitAspect.java` → Spring Data Redis + Lua AOP模式
- `D:\MyCode\idea_projects\flash-sale-platform\...\dto\Result.java` → 统一响应体风格
- `D:\MyCode\idea_projects\flash-sale-platform\...\utils\RedisConstants.java` → Redis Key管理方式

## 验证方式

1. 启动Docker Desktop中 pgvector 和 Redis 容器，确认端口可访问
2. IDEA中启动Spring Boot应用，检查启动日志无报错
3. Postman/Knife4j（Swagger）测试完整链路：
   - 上传大文件（分片）→ 秒传验证
   - 上传简历 → 等待异步分析完成 → 查看分析结果
   - 上传知识库文档 → 向量化 → RAG问答（SSE流式）
   - 创建面试 → 逐一答题 → 等待评估 → 查看报告
   - 限流验证：快速连续请求触发429
4. 启动原前端项目，验证前后端联调
