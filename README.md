# AI-Interview

AI 模拟面试平台：简历 AI 分析、知识库 RAG 问答、模拟面试（AI 出题 + 异步评估）、大文件分片上传。

- 后端（本仓库）：Spring Boot 4 + Java 21 + MyBatis-Plus + PostgreSQL/pgvector + Redis Stream + OSS + 通义千问
- 前端：`D:\MyCode\FrontEndCode\ai-interview`（Vue 3 + Element Plus）

## 目录

- `CLAUDE.md` — 开发强制规范（开发流程 / 编码规范 / 文档机制）
- `docs/` — 跨会话知识库：现状基线、模块实现文档、数据模型、接口清单、任务清单、功能设计
- `src/main/resources/prompts/` — LLM 提示词模板（.st）
- `src/main/resources/defense/` — Prompt 注入防护模式配置

## 流程规范

本项目遵循 `CLAUDE.md` 的开发规范，各阶段产出在 `docs/`：

1. 开发前先读 `docs/00-现状基线.md` + `docs/03-任务清单.md` + 相关 `docs/modules/` 模块文档
2. 每个任务走六步闭环（立项 → 设计 → 实现 → 自动验收 → 用户复核 → 用户手动提交）
3. 每次代码更改后同步更新相关参考文档（modules/、00/01/02）

## 启动

1. Docker 启动 `postgres-service`(5432) 与 `my_redis`(6380)
2. 配置本地密钥：复制 `.env.example` → `.env`，填入 DashScope API Key 与 OSS AccessKey（`cp .env.example .env`）
3. IDEA 运行 `Application`（或 `mvn spring-boot:run`）——密钥经 **EnvFile 插件**注入 `.env`，或设置为同名系统环境变量；未配置时应用可启动但 LLM/OSS 功能不可用
4. Swagger 在 `http://localhost:8080/swagger-ui.html`；前端 `pnpm dev`（http://localhost:5173）

## 密钥说明

- 仓库内零明文密钥；`.env.example` 是占位模板（可提交），`.env` 被 gitignore 排除
- 涉及密钥提交规范见 `CLAUDE.md`；推送 GitHub 前请在控制台轮换旧密钥（历史提交含旧值）
