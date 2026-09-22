# 01-RAG 会话命名与重命名

> 阶段B设计产出 · 状态：已实现（T21，2026-09-22） · 日期：2026-09-22

## 1. 目标

用户能**给新会话命名**、也能**给已有会话重命名**。当前所有会话标题都是前端写死的「N个知识库对话」，开两个会话就完全分不清。

## 2. 现状（已核实）

- 创建会话：`KnowledgePage.vue` 传死标题 `chatKbIds.length + '个知识库对话'`；后端 `RagChatServiceImpl.createSession` 的兜底也是同义的 `kbIds.size() + " 个知识库对话"`
- **没有重命名接口**：`RagChatController` 共 6 个端点、`IRagChatService` 共 5 个方法，均无 update/rename；建会话后唯一会写 `rag_chat_sessions` 的地方是 `sendMessage` 里刷 `updated_at`
- 表：`rag_chat_sessions(session_title VARCHAR(200), kb_ids TEXT, is_pinned BOOLEAN, created_at, updated_at)`
- 列表排序：`isPinned DESC, updatedAt DESC`；`is_pinned` **恒为 false**（没有任何 UI 能把它置 true），所以排序实际由 `updatedAt` 单独决定
- 「更新时间」列显示的正是 `updated_at`，语义上等于**最后一次对话时间**（每轮问答刷新）

## 3. 方案

### 3.1 后端：新增 `PUT /api/rag-chat/sessions/{id}/title`

对齐既有 `PUT /api/knowledge/{id}/category` 的风格（本仓库唯一已有的"改单个字段"先例），路径带 `/title` 子资源，避免 `PUT /sessions/{id}` 那种"哪些字段没传=不改"的模糊语义。

- 新增 `knowledge/dto/RagRenameSessionRequest`：`@NotBlank @Size(max=50) String title`（`jakarta.validation` 已在用，`WebExceptionHandler` 已能把校验失败映射成 HTTP 200 + `code=400` + 具体 message）
- `IRagChatService.renameSession(Long sessionId, String title)`，实现：
  1. `title` trim 后为空或超长 → `BusinessException(BAD_REQUEST, …)`
  2. 会话不存在 → `BusinessException(KNOWLEDGE_BASE_NOT_FOUND, "会话不存在")`——**与 `getSessionDetail`/`sendMessage` 完全一致，不新增 ErrorCode**（新增会牵动既有 4 处调用点 + 两处文档，属顺手重构）
  3. 落库用 `LambdaUpdateWrapper.set(sessionTitle)`，**只 set 标题一列**
- 返回 `Result<String>` = `"标题已更新"`

**关键决策：重命名不碰 `updated_at`。**
- `updated_at` 在本项目 = 最后一次对话时间，同时也是列表排序键。重命名是元数据编辑，若刷它，一周前的会话会突然跳到列表顶部，「更新时间」列也会变成"刚刚"——既误导又让该列失去意义
- 因此**不能用 `updateById`**：本仓库没有 `MetaObjectHandler`，`updateById` 会把"读到的旧快照"里包括 `updated_at` 在内的所有非 null 字段写回，与并发的 `sendMessage` 抢写，可能把 `updated_at` 改小（排序倒退）。`LambdaUpdateWrapper` 的 SET 子句只有一列，从结构上杜绝

### 3.2 前端

- `api/rag-chat.js` 增加 `renameSession(id, title)`（走 axios 拦截器，自动解包 Result）
- 历史会话表操作列增加「重命名」按钮 → `ElMessageBox.prompt` 预填当前标题 → 成功后**就地把 `row.sessionTitle` 改掉**（不重拉列表，避免排序跳动的视觉噪音）。按钮必须 `@click.stop`（该行已有 `@row-click="openSession"`）
- 创建会话改为先 `ElMessageBox.prompt` 输入标题，留空/取消则用默认 `N个知识库对话`（取消会 reject `'cancel'`，按现有 `doDeleteSession` 的写法吞掉，不当成错误弹提示）

### 3.3 明确不做

- **置顶**：`is_pinned` 恒 false 且无 UI，属另一个功能
- **按首问自动起标题**（LLM 命名）：额外 LLM 调用，收益不确定，不在本次
- **不改 `deleteSession` 的存在性校验**（现状删不存在的 id 也返回成功），保持本任务单一职责

## 4. 接口契约

| 方法 | 路径 | 入参 | 出参 | 备注 |
|---|---|---|---|---|
| PUT | `/api/rag-chat/sessions/{id}/title` | `{title: string}` | `"标题已更新"` | title 去空格后非空、≤50 字 |

错误：标题空/超长 → `code=400` + 「会话标题不能为空 / 不能超过 50 个字符」；会话不存在 → `code=6001` + 「会话不存在」。均沿用统一响应体（HTTP 恒 200）。

端点总数 30 → 31。

## 5. 表

**无 DDL 变更**，复用 `rag_chat_sessions.session_title`。

接口层限 50 字而不是贴 DB 的 `VARCHAR(200)`：50 是产品上限（会话标题就是列表里一格），留足余量让超长永远在接口层被拦下，不会掉进 Postgres `value too long` → 500「系统繁忙」那种糟糕的错误形态。

## 6. 验收标准

1. 创建会话时可输入标题；留空或取消 → 用默认标题
2. 会话列表点「重命名」→ 输入新标题 → 表格标题即时变化，且**不触发整表重载**
3. **重命名后该行在列表中的位置不变，「更新时间」列的值也不变**（核心回归点）
4. 空标题 / 超过 50 字 → 返回业务错误提示，**不是 500**
5. 对不存在的会话 id 重命名 → 「会话不存在」
6. curl 走一遍接口 + 浏览器走一遍交互

## 7. 需同步的文档

- `docs/02-接口清单.md`：§4 新增该行 + §7 变更登记 + 端点总数 30→31
- `docs/modules/04-知识库与RAG.md`：§2「6 端点」→「7 端点」、§4 写清"只改标题不动 updatedAt"、§7 验证要点加一条
- `docs/03-任务清单.md`：T21 状态 + 验收记录
- `docs/01-数据模型.md`：无需改（表结构不变）
