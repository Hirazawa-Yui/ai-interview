-- ============================================================
-- AI-Interview 数据库 Schema（PostgreSQL 16）
-- 库：ai_interview（localhost:5432，postgres/123456）
-- 说明：本项目无迁移工具（无 Flyway/Liquibase），建表/改表手动执行本文件，
--       并同步更新 docs/01-数据模型.md。
-- 注意：vector_store 表由 Spring AI 自动创建（initialize-schema: true，
--       HNSW/COSINE/1024 维），不在本文件内。
-- ============================================================

-- ---------- 文件信息（Phase 2，分片上传秒传去重） ----------
CREATE TABLE IF NOT EXISTS file_info (
    id           BIGSERIAL PRIMARY KEY,
    file_md5     VARCHAR(32) NOT NULL,             -- 完整文件 MD5（Hex）
    file_name    VARCHAR(500),                     -- 原始文件名
    file_size    BIGINT,                           -- 字节
    content_type VARCHAR(100),
    storage_key  VARCHAR(1000),                    -- OSS Key
    storage_url  VARCHAR(2000),                    -- OSS 访问 URL
    status       VARCHAR(20) DEFAULT 'COMPLETED',
    created_at   TIMESTAMP DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_file_info_md5 ON file_info(file_md5);

-- ---------- 简历（Phase 4） ----------
CREATE TABLE IF NOT EXISTS resumes (
    id                BIGSERIAL PRIMARY KEY,
    file_md5          VARCHAR(32),                 -- MD5 去重
    original_filename VARCHAR(500),
    file_size         BIGINT,
    content_type      VARCHAR(100),
    storage_key       VARCHAR(1000),
    storage_url       VARCHAR(2000),
    parsed_text       TEXT,                        -- Tika 解析纯文本
    analyze_status    VARCHAR(20) DEFAULT 'PENDING',  -- PENDING/PROCESSING/COMPLETED/FAILED
    analyze_error     VARCHAR(1000),
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

-- ---------- 简历 AI 分析结果（Phase 4，一对多） ----------
CREATE TABLE IF NOT EXISTS resume_analyses (
    id               BIGSERIAL PRIMARY KEY,
    resume_id        BIGINT NOT NULL,
    overall_score    INT,                          -- 0-100
    summary          TEXT,
    strengths_json   TEXT,                         -- JSON 数组 ["..."]
    suggestions_json TEXT,                         -- JSON 数组 [{category,issue,suggestion}]
    ai_raw_response  TEXT,
    analyzed_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_resume_analyses_resume ON resume_analyses(resume_id);

-- ---------- 知识库文档（Phase 5a） ----------
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id                BIGSERIAL PRIMARY KEY,
    file_md5          VARCHAR(32),
    kb_name           VARCHAR(200),
    category          VARCHAR(100),
    original_filename VARCHAR(500),
    file_size         BIGINT,
    content_type      VARCHAR(100),
    storage_key       VARCHAR(1000),
    parsed_text       TEXT,
    vector_status     VARCHAR(20) DEFAULT 'PENDING',  -- PENDING/PROCESSING/COMPLETED/FAILED
    vector_error      VARCHAR(1000),
    chunk_count       INT DEFAULT 0,
    question_count    INT DEFAULT 0,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

-- ---------- RAG 会话（Phase 5b） ----------
CREATE TABLE IF NOT EXISTS rag_chat_sessions (
    id            BIGSERIAL PRIMARY KEY,
    session_title VARCHAR(200),
    kb_ids        TEXT,                           -- JSON 数组字符串，如 "[1,2]"
    is_pinned     BOOLEAN DEFAULT FALSE,
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()
);

-- ---------- RAG 消息（Phase 5b） ----------
CREATE TABLE IF NOT EXISTS rag_chat_messages (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT NOT NULL,
    role          VARCHAR(20) NOT NULL,           -- user / assistant
    content       TEXT,
    message_order INT DEFAULT 0,
    completed     BOOLEAN DEFAULT TRUE,           -- AI 消息流式输出中为 false
    created_at    TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_messages_session ON rag_chat_messages(session_id, message_order);

-- ---------- 面试会话（Phase 6a） ----------
CREATE TABLE IF NOT EXISTS interview_sessions (
    id               BIGSERIAL PRIMARY KEY,
    resume_id        BIGINT,                      -- 关联简历（可为 NULL）
    jd_text          TEXT,                        -- 岗位 JD
    direction        VARCHAR(50) NOT NULL,        -- frontend/backend/test/algorithm/data/devops/fullstack
    question_count   INT DEFAULT 8,
    current_question INT DEFAULT 0,               -- 0=未开始
    status           VARCHAR(20) DEFAULT 'CREATED',  -- CREATED/IN_PROGRESS/COMPLETED/EVALUATING/EVALUATED/FAILED
    questions_json   TEXT,                        -- [{questionNumber,questionText,tags}]
    evaluate_error   VARCHAR(500),
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW()
);

-- ---------- 面试回答（Phase 6a） ----------
CREATE TABLE IF NOT EXISTS interview_answers (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    question_number INT NOT NULL,                 -- 题号 1-based
    question_text   TEXT NOT NULL,                -- 题目文本（冗余存储）
    answer_text     TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_interview_answers_session ON interview_answers(session_id, question_number);

-- ---------- 面试评估（Phase 6b，一对多） ----------
CREATE TABLE IF NOT EXISTS interview_evaluations (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT NOT NULL,
    overall_score     INT,                        -- 0-100
    summary           TEXT,
    per_question_json TEXT,                       -- [{questionNumber,score,comment}]
    strengths_json    TEXT,                       -- JSON 数组
    improvements_json TEXT,                       -- JSON 数组 [{category,issue,suggestion}]
    ai_raw_response   TEXT,
    evaluated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_interview_eval_session ON interview_evaluations(session_id);
