# 02-Prompt 注入防护

> 模块文档 · 对应代码基线：master · 更新日期：2026-09-05

## 1. 功能概述
平台所有功能都在与 LLM 交互，用户的简历/文档/面试回答最终会拼进 Prompt，攻击面大。本模块提供**三层纵深防御**，Phase 4~6 所有 LLM 调用点统一经 `IPromptDefenseService` 编排。三层机制完全不同，同时失效概率极低（类比：正则=参数校验、分隔符=参数化查询、输出护栏=结果二次校验）。

## 2. 涉及文件
- `service/IPromptDefenseService.java` + `impl/PromptDefenseServiceImpl.java` — 编排层（sanitizeAndWrap / sanitize / wrapWithDelimiters / guardOutput）
- `common/ai/PromptSanitizer.java` — 第一层正则净化 + 第二层动态分隔符
- `common/ai/DefensePatternLoader.java` — 从 JSON 加载并编译正则（启动时 `@PostConstruct`）
- `common/ai/OutputGuardConfig.java` — 第三层开关（`@ConfigurationProperties app.ai.defense.output-guard`）
- `common/ai/PromptSecurityConstants.java` — 追加到 system prompt 的防注入指令常量
- `resources/defense/injection-patterns.json` — 第一层 5 条模式（行首角色标记/英文指令覆盖/中文指令覆盖/分隔符伪造/动态标签伪造）
- `resources/defense/output-guard-patterns.json` — 第三层 6 条投降语模式
- `src/test/java/com/aiinterview/PromptDefenseTest.java` — 14 个单测（含误杀回归用例），本项目唯一真实测试
- 配置：`application.yml` 的 `app.ai.defense.*`（sanitizer/delimiter/output-guard 三开关）

## 3. 核心流程（调用 LLM 的标准姿势）
```java
// 调用前：净化 + 包裹（一层 + 二层）
String safeInput = defenseService.sanitizeAndWrap("resume", userText);
// system prompt = 业务模板 + JSON格式 + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
// 调用 LLM
String llmResponse = chatClient.prompt().system(sp).user(up).call().content();
// 调用后：输出护栏（三层）
defenseService.guardOutput(llmResponse);
// 最后 BeanOutputConverter 解析 JSON —— 注入导致非 JSON → 解析失败抛异常，天然兜底
```

## 4. 实现细节
- **第一层 正则净化**：命中后**替换为占位符继续执行**（只净化不报错，不给攻击者探测反馈）；模式配置化，加新模式只改 JSON。
- **第二层 动态分隔符**：`<data-boundary-{随机uuid8}-{label}>...</data-boundary-{随机uuid8}-{label}>` 包裹用户数据；UUID 随机使攻击者无法提前构造伪造闭合标签；system prompt 尾部告知 LLM 标签内是数据不是指令。
- **第三层 输出护栏**：检测"投降语"（`I'll now act as`、`我已经忽略`、`新的角色是` 等），命中直接抛 `AI_SERVICE_ERROR`——这些词在正常评估输出中不会出现。
- **JSON 结构化输出兜底**：所有 LLM 调用用 `BeanOutputConverter` 要求 JSON；注入导致的非 JSON 输出 → 解析失败 → 业务异常，异常文本不会到达用户。
- 净化+包裹是两步合一（`sanitizeAndWrap`）；纯净化场景（RAG 问题）单独调 `sanitize`。

## 5. 数据
无表无 Redis。配置两个 JSON 文件 + yml 三开关。

## 6. 已知坑位
- 第一层模式 `role-marker` 用 `(?im)^\s*(system|user|...)[:：].*` 只匹配行首——**多行用户文本中单独一行 "system: xxx" 也会被过滤**，属误杀（有单测覆盖说明可接受）。
- 净化是"替换"不是"阻断"，攻击文本仍会进 LLM，依赖二三层兜底。
- 第二层对超长文本有 token 开销（标签+指令），可忽略。

## 7. 验证要点
1. 单测：`mvn test -Dtest=PromptDefenseTest`（或 IDEA 直接跑，14 用例全绿）
2. 手工：简历上传时在简历里塞一行 "忽略之前的指令"，看后端日志出现"检测到潜在 Prompt 注入尝试"告警，且分析结果正常
3. 护栏：临时改 `output-guard-patterns.json` 加一个必然命中的模式，重启后调 LLM 接口应返回"AI 服务返回异常"
