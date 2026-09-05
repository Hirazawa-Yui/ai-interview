# AI-Interview 开发规范

## 一、项目总览
- 后端 `d:\MyCode\idea_projects\AI-Interview`（本仓库）：Spring Boot 4 + Java 21 + MyBatis-Plus + PostgreSQL/pgvector + Redis Stream + OSS + 通义千问
- 前端 `D:\MyCode\FrontEndCode\ai-interview`：Vue 3 + Element Plus（无版本控制，改动前先与用户确认）
- 现状：Phase 1–5 已完成并提交；Phase 6 模拟面试代码已完成、待提交。详见 `docs/00-现状基线.md`
- 本规范取代 PLAN.md；**`docs/` 是跨会话的唯一知识来源**：技术栈、模块实现、坑位都以文档为准，不依赖对话记忆

## 二、开发流程

### 阶段 A：现状基线固化（一次性）
- **动作**：① 提交 Phase 6 全部代码；② 补齐 `docs/sql/schema.sql` 与接口清单；③ 端到端验收现状；④ 修复缺陷
- **规则**：每个提交/修复单独给用户确认，不合并推进

### 阶段 B：功能迭代（循环）
每个任务走六步闭环，**完成一步停一步**：
1. **立项**：任务记入 `docs/03-任务清单.md`
2. **设计**：涉及新表/新接口/新页面的功能，先写 `docs/design/NN-xxx设计.md`（目标/方案/接口/表/验收标准），**用户确认后才动代码**；纯 bug 修复跳过
3. **实现**：原子化——一次只交付一个最小可验收用户功能，前后端一起完成；遵守第三节规范
4. **自动验收**：启动前后端，用 curl/浏览器走核心链路，证据写入任务验收记录
5. **用户复核**：给出页面操作路径请用户确认；通过后更新清单状态
6. **提交**：Claude 给出 git 命令，**由用户手动执行**；提交后进入下一任务

### 阶段 C：收尾（可选）
- 部署、界面美化等，走同一闭环

## 三、编码规范

### 后端
1. 扁平分包 `com.aiinterview`；controller 薄、service 厚；接口 + Impl
2. 统一 `Result<T>` / `ErrorCode`（模块编号 1xxx–9xxx）/ `BusinessException` / `WebExceptionHandler`
3. **敏感信息（API Key、AccessKey、密码）一律环境变量，禁止硬编码**进 yml/测试类
4. Redis key 一律走 `RedisKeys`/`StreamKeys` 常量，禁止散落字面量
5. 异步任务走 `AbstractStreamProducer`/`AbstractStreamConsumer` 模板，禁止 `new Thread`
6. LLM 输出解析前必须做防御处理（json 围栏剥离 + PromptDefense 输出护栏）
7. 建表/改表必须同步更新 `docs/sql/schema.sql`（无迁移工具，手动执行）
8. 新增/变更接口必须同步 `docs/02-接口清单.md`

### 前端
1. 接口调用统一放 `src/api/`，禁止页面内裸 fetch（现有 RAG 接口收敛属任务队列）
2. AI 输出的 HTML 渲染前必须 DOMPurify 消毒
3. 定时器/轮询在 `onBeforeUnmount` 清理
4. 不引入 TS/lint 等新工具链（除非用户明确同意）

### 通用
- 一个任务只改一个功能模块，不做顺手重构
- **每次代码更改完成后，必须检查是否需要同步更新相关参考文档**：改模块 → 更新 `docs/modules/` 对应文档（功能变化、坑位、验证要点）；改表/接口/key → 更新 `docs/01`、`docs/02`；跨模块变化 → 更新 `docs/00`；与代码同任务交付，不欠文档债
- 新踩的坑立即记录（本模块文档，跨模块的记入 00），不等事后补

## 四、文档与检查机制（持久知识库）
| 文件 | 内容 | 性质 |
|---|---|---|
| `docs/00-现状基线.md` | 技术栈、基础设施、功能矩阵、横切机制（Result/异常/Stream 模板/配置类）、跨模块坑位 | 参考 |
| `docs/modules/NN-模块名.md` | **每功能模块一份**：功能概述/涉及文件/核心流程/实现细节/数据/坑位/验证要点（七节模板） | 参考 |
| `docs/01-数据模型.md` + `docs/sql/schema.sql` | 数据库表 + Redis/Stream key 清单 + 可执行 DDL | 参考+可执行 |
| `docs/02-接口清单.md` | 全部 REST/SSE 接口契约 | 参考 |
| `docs/03-任务清单.md` | **活文档，唯一事实来源**：任务表 + 每任务验收记录 | 运行时 |
| `docs/design/NN-xxx设计.md` | 新功能设计（阶段 B 第 2 步产出） | 按需 |

## 五、已知坑位（跨模块，持续补充）
- Boot 4 下 MyBatis-Plus / Redisson / Spring AI 自动配置不可用 → 手动配置类；**新增依赖先验证自动配置兼容性**
- Spring AI 2.0.0-M4 依赖 milestone 仓库
- Redis Stream 消费组不存在时 `createGroup` 失败被吞 → 消费前确保组已建（MKSTREAM）
- pgvector 由 Spring AI 自动建表（`initialize-schema: true`）
- 前端无版本控制：改前端文件前先告知用户，重大改动建议先备份

## 六、执行指令
- **每轮对话开始**：先读 `docs/00-现状基线.md` + `docs/03-任务清单.md`，再读本次涉及的 `docs/modules/` 模块文档；动代码前必须掌握既有实现
- **一个任务完成**：更新任务清单 + 检查并同步相关参考文档（modules/、00/01/02）+ 输出验收记录与 git 提交命令，**停下等用户确认**，不擅自开始下一任务
