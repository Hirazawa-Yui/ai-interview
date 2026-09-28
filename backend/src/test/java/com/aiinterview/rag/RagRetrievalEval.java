package com.aiinterview.rag;

import com.aiinterview.Application;
import com.aiinterview.knowledge.entity.KnowledgeBase;
import com.aiinterview.knowledge.mapper.KnowledgeBaseMapper;
import com.aiinterview.knowledge.service.IKbVectorService;
import com.aiinterview.knowledge.splitter.OverlapTextSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索效果评测（T29）。
 *
 * <p><b>做什么</b>：对人工标注的问答集跑 4 组消融对照（原问题/改写后 × 固定topK/动态topK）
 * 与 minScore 敏感性扫描，产出两套口径的指标——题目级 HitRate@1/@3/@5、片段级 Recall@5、
 * 以及 MRR@5。只检索<b>不生成</b>——不碰 {@code answerQuestionStream}，所以不消耗生成侧 token。
 *
 * <p><b>为什么用 main 而不是 @SpringBootTest</b>：沿用本仓库 {@code OSSTest}/{@code AIConnectionTest}
 * 的先例；评测要连真库、烧 token，不该混进 {@code mvn test}。Web 环境设为 NONE，与
 * 已在跑的 8080 实例不冲突。
 *
 * <p><b>运行前置</b>：PG(5432) / Redis(6380) / DASHSCOPE_API_KEY 就绪；目标知识库
 * {@code vector_status=COMPLETED} 且<b>没有在途的向量化任务</b>（否则评测进程的消费者线程
 * 会和主后端抢消息）。从 {@code backend/} 目录运行。
 *
 * <p><b>可复现性</b>：改写结果首次运行后写回 {@code rag-gold.json} 的 {@code rewritten} 字段并冻结，
 * 之后每组读同一份改写文本——否则 B/C 两组各调一次 LLM，用的是两次不同的改写，对比无意义。
 */
public class RagRetrievalEval {

    // ========== 与生产对齐的参数（同步点：KbQueryServiceImpl L183-193） ==========

    /** 固定 topK 组的取值；同时保证 @1/@3/@5 三档都可计算（最小 topK 是 8 > 5） */
    private static final int FIXED_TOPK = 8;
    /** 对照实验统一固定的 minScore——不固定就同时变了两个变量，rewrite 的净效果无法归因 */
    private static final double FIXED_MIN_SCORE = 0.28;
    /** 只报 @5 以内的指标：所有组的 topK 都 ≥ 8，@5 对每组都公平可算 */
    private static final int[] K_VALUES = {1, 3, 5};
    /**
     * minScore 敏感性扫描档位。前四档是配置里出现过的值，实测完全无差异——
     * 因为全部低于该语料的相似度下限；后五档向上延伸，才是阈值真正开始起作用的区间。
     */
    private static final double[] MIN_SCORE_SWEEP = {0.10, 0.18, 0.28, 0.40, 0.50, 0.55, 0.60, 0.65, 0.70};

    /** 生产 Environment，在 main 里赋值。抽成静态字段只为让 resolveTopK 与生产写法一致 */
    private static Environment CTX_ENV;

    // ========== 数据模型 ==========

    public static class GoldSet {
        public long kbId;
        public String kbName;
        public String annotatedAt;
        public String annotator;
        public String note;
        public List<GoldCase> cases = new ArrayList<>();
    }

    public static class GoldCase {
        public int id;
        /** 问题形态：规范 / 词汇不匹配 / 短查询。决定该题用来检验哪个机制（见 docs/04 §2） */
        public String form;
        public String type;
        public String question;
        /** 首次运行由本脚本写入并冻结；之后所有组复用同一份，保证可比 */
        public String rewritten;
        public List<String> gold = new ArrayList<>();
        public String note;
    }

    private record Group(String key, String label, boolean useRewritten, boolean dynamicTopK, double minScore) {}

    private static final List<Group> GROUPS = List.of(
            new Group("A", "原问题 + topK8（基线）      ", false, false, FIXED_MIN_SCORE),
            new Group("B", "改写后 + topK8（rewrite 净效果）", true, false, FIXED_MIN_SCORE),
            new Group("C", "改写后 + 动态topK（现状）   ", true, true, FIXED_MIN_SCORE),
            new Group("D", "原问题 + 动态topK（设计意图）", false, true, FIXED_MIN_SCORE)
    );

    /**
     * 单组单题的检索结果。
     *
     * <p>记录<b>每个 gold 片段各自的命中排位</b>（0 = 未命中），而不是只记"有没有命中"——
     * 对比型/多跳型题目的答案天然横跨 2-3 个片段，若只按"命中任一"判定，
     * 检索到半截也会记满分，指标会系统性偏高。有了逐片段排位就能同时算出
     * 题目级 HitRate（宽松）与片段级 Recall（严格）两套口径。
     */
    private record Probe(List<Integer> fragRanks, int docCount, int ctxChars, int topK) {
        /** 本题最早命中的排位；全部未命中返回 0 */
        int firstHitRank() {
            return fragRanks.stream().filter(r -> r > 0).mapToInt(Integer::intValue).min().orElse(0);
        }

        /** 前 k 名里命中了几段 gold */
        int fragHitAt(int k) {
            return (int) fragRanks.stream().filter(r -> r > 0 && r <= k).count();
        }
    }

    // ========== 入口 ==========

    public static void main(String[] args) throws Exception {
        Path goldPath = resolveGoldPath();
        ObjectMapper mapper = JsonMapper.builder().build();
        GoldSet gold = mapper.readValue(goldPath.toFile(), GoldSet.class);
        System.out.println("标注集: " + goldPath.toAbsolutePath());
        System.out.println("知识库: kbId=" + gold.kbId + " 「" + gold.kbName + "」 题数=" + gold.cases.size());

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(args);

        try {
            IKbVectorService vectorService = ctx.getBean(IKbVectorService.class);
            ChatClient chatClient = ctx.getBean(ChatClient.class);
            KnowledgeBaseMapper kbMapper = ctx.getBean(KnowledgeBaseMapper.class);
            Environment env = ctx.getBean(Environment.class);
            CTX_ENV = env;

            // ---- 0. 标注集自检：gold 片段必须能在原文里逐字找到，且要有区分度 ----
            KnowledgeBase kb = kbMapper.selectById(gold.kbId);
            if (kb == null) throw new IllegalStateException("知识库不存在: " + gold.kbId);
            if (kb.getParsedText() == null || kb.getParsedText().isBlank()) {
                throw new IllegalStateException("知识库 parsedText 为空，无法自检标注集");
            }
            String parsedText = kb.getParsedText();
            System.out.println("原文长度=" + parsedText.length() + " 字符，chunkCount=" + kb.getChunkCount());
            validateGold(gold, parsedText,
                    env.getProperty("app.ai.rag.chunk.size", Integer.class, 500),
                    env.getProperty("app.ai.rag.chunk.overlap", Integer.class, 80),
                    kb.getChunkCount());

            // ---- 1. 冻结改写结果（首次运行调 LLM，之后复用） ----
            freezeRewrites(gold, goldPath, mapper, chatClient);

            // ---- 2. 四组消融对照 ----
            Map<String, List<Probe>> byGroup = new LinkedHashMap<>();
            for (Group g : GROUPS) {
                List<Probe> probes = new ArrayList<>();
                for (GoldCase c : gold.cases) {
                    probes.add(probe(vectorService, gold.kbId, c, g));
                }
                byGroup.put(g.key(), probes);
            }

            // ---- 3. minScore 敏感性（原问题 + topK8，只变 minScore） ----
            Map<Double, List<Probe>> byMinScore = new LinkedHashMap<>();
            for (double ms : MIN_SCORE_SWEEP) {
                Group g = new Group("ms" + ms, "", false, false, ms);
                List<Probe> probes = new ArrayList<>();
                for (GoldCase c : gold.cases) {
                    probes.add(probe(vectorService, gold.kbId, c, g));
                }
                byMinScore.put(ms, probes);
            }

            // ---- 4. 输出 ----
            StringBuilder report = new StringBuilder();
            report.append(rewriteStats(gold)).append('\n');
            report.append(ablationTable(gold, byGroup)).append('\n');
            report.append(formTable(gold, byGroup)).append('\n');
            report.append(topkTierTable(gold, byGroup)).append('\n');
            report.append(typeStratifiedTable(gold, byGroup)).append('\n');
            report.append(minScoreTable(byMinScore)).append('\n');
            report.append(missDetail(gold, byGroup));

            System.out.println(report);

            Path out = Path.of("target", "rag-eval");
            Files.createDirectories(out);
            Files.writeString(out.resolve("result.txt"), report.toString(), StandardCharsets.UTF_8);
            System.out.println("原始结果已写入: " + out.resolve("result.txt").toAbsolutePath());
        } finally {
            ctx.close();
        }
    }

    // ========== 标注集自检 ==========

    /**
     * 硬校验。关键手法：用与生产同一个 {@link OverlapTextSplitter} 在原文上<b>离线重建出
     * chunk 序列</b>（分块器是确定性纯算法），再拿 gold 片段去比对——这样校验的是
     * "片段能否真的命中某个 chunk"，而不只是"片段在原文里存在"。
     *
     * <p>三类问题：① 任何 chunk 都匹配不上（抄错，或片段跨了块边界）→ 该题必然永远判 miss；
     * ② 匹配到过多 chunk（泛化句，如"这是一个问题"）→ 命中判定失去区分度；
     * ③ 重建块数与库里 chunkCount 不符 → 分块参数与入库时不一致，整个评测失效。
     */
    private static void validateGold(GoldSet gold, String parsedText, int chunkSize, int overlap,
                                     Integer expectedChunkCount) {
        List<String> chunks = new OverlapTextSplitter(chunkSize, overlap)
                .split(List.of(new Document(parsedText)))
                .stream().map(d -> squash(d.getText())).toList();

        System.out.println("\n===== 标注集自检 =====");
        System.out.printf("  离线重建 chunk 数=%d，库中 chunkCount=%s%n",
                chunks.size(), expectedChunkCount);
        if (expectedChunkCount != null && expectedChunkCount > 0 && chunks.size() != expectedChunkCount) {
            System.out.println("  ⚠️ 重建块数与库中不一致！分块参数可能已被改动，本次评测结果不可比。");
        }

        int bad = 0, loose = 0;
        // 片段 → 首个命中的 chunk 下标，用于查「两道不同题目其实指向同一块」的伪多样性
        Map<Integer, Integer> firstChunkOf = new LinkedHashMap<>();
        Map<Integer, Integer> ownerCaseOf = new LinkedHashMap<>();
        int sameChunk = 0;

        for (GoldCase c : gold.cases) {
            if (c.gold == null || c.gold.isEmpty()) {
                System.out.printf("  [%2d] 没有 gold 片段%n", c.id);
                bad++;
                continue;
            }
            for (String g : c.gold) {
                String sg = squash(g);
                List<Integer> hitIdx = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    if (chunks.get(i).contains(sg)) hitIdx.add(i);
                }
                if (hitIdx.isEmpty()) {
                    System.out.printf("  [%2d] ✗ 无任何 chunk 包含该片段（抄错或跨块）: %s%n",
                            c.id, abbreviate(g));
                    bad++;
                } else if (hitIdx.size() > 3) {
                    System.out.printf("  [%2d] ⚠ 片段命中 %d 个块，区分度低: %s%n",
                            c.id, hitIdx.size(), abbreviate(g));
                    loose++;
                }
                if (!hitIdx.isEmpty()) {
                    int prim = hitIdx.get(0);
                    Integer prev = firstChunkOf.get(prim);
                    if (prev != null && prev != c.id) {
                        sameChunk++;
                        System.out.printf("  [%2d] ⚠ 与题目 [%d] 指向同一块(chunk %d)，两题检索结果相关: %s%n",
                                c.id, ownerCaseOf.get(prim), prim, abbreviate(g));
                    } else if (prev == null) {
                        firstChunkOf.put(prim, c.id);
                        ownerCaseOf.put(prim, c.id);
                    }
                }
            }
        }
        System.out.printf("  结论: %d 条无效 / %d 条区分度偏低 / %d 条与他题撞块 / 共 %d 题%n",
                bad, loose, sameChunk, gold.cases.size());
        if (bad > 0) {
            System.out.println("  ⚠️ 存在无效片段，这些题会恒判 miss，务必修正后再采信指标");
        }
    }

    // ========== 改写结果冻结 ==========

    private static void freezeRewrites(GoldSet gold, Path goldPath, ObjectMapper mapper,
                                       ChatClient chatClient) throws Exception {
        String rewritePrompt;
        try {
            rewritePrompt = new ClassPathResource("prompts/kb-query-rewrite.st")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 prompts/kb-query-rewrite.st", e);
        }

        int missing = 0;
        for (GoldCase c : gold.cases) {
            if (c.rewritten != null && !c.rewritten.isBlank()) continue;
            missing++;
            c.rewritten = rewrite(chatClient, rewritePrompt, c.question);
        }
        if (missing > 0) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(goldPath.toFile(), gold);
            System.out.println("\n已冻结 " + missing + " 条改写结果并写回 " + goldPath.getFileName()
                    + "（后续运行复用同一份，保证各组用同一改写文本）");
        } else {
            System.out.println("\n改写结果已存在，本次全部复用（0 次 LLM 调用）");
        }
    }

    /** 与 KbQueryServiceImpl.rewriteQuestion 同构（空历史）：>200 字或异常则回退原问题 */
    private static String rewrite(ChatClient chatClient, String prompt, String question) {
        try {
            String userMsg = prompt.replace("{question}", question).replace("{history}", "");
            String result = chatClient.prompt().user(userMsg).call().content();
            if (result != null && !result.isBlank() && result.length() <= 200) {
                return result.trim();
            }
        } catch (Exception e) {
            System.out.println("  改写失败，回退原问题: " + e.getMessage());
        }
        return question;
    }

    private static String rewriteStats(GoldSet gold) {
        int changed = 0, total = 0, lenSum = 0;
        List<String> samples = new ArrayList<>();
        for (GoldCase c : gold.cases) {
            total++;
            String r = c.rewritten == null ? c.question : c.rewritten;
            lenSum += r.length();
            if (!r.equals(c.question)) {
                changed++;
                if (samples.size() < 3) samples.add(abbreviate(c.question) + "  →  " + abbreviate(r));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 改写统计 =====\n");
        sb.append(String.format("  改写改变了原问题: %d/%d (%.0f%%)，平均长度 %.1f 字%n",
                changed, total, 100.0 * changed / total, (double) lenSum / total));
        if (!samples.isEmpty()) {
            sb.append("  样例:\n");
            samples.forEach(s -> sb.append("    ").append(s).append('\n'));
        }
        return sb.toString();
    }

    // ========== 检索 ==========

    private static Probe probe(IKbVectorService vectorService, long kbId, GoldCase c, Group g) {
        String query = g.useRewritten() ? c.rewritten : c.question;
        int topK = g.dynamicTopK() ? resolveTopK(query) : FIXED_TOPK;
        return probe(vectorService, kbId, query, g.minScore(), topK, c.gold);
    }

    private static Probe probe(IKbVectorService vectorService, long kbId, String query,
                               double minScore, int topK, List<String> gold) {
        List<Document> docs = vectorService.similaritySearch(query, List.of(kbId), topK, minScore);
        List<String> texts = docs.stream().map(d -> squash(d.getText())).toList();
        List<Integer> fragRanks = new ArrayList<>();
        for (String frag : gold) {
            String sf = squash(frag);
            int rank = 0;
            for (int r = 0; r < texts.size(); r++) {
                if (texts.get(r).contains(sf)) { rank = r + 1; break; }
            }
            fragRanks.add(rank);
        }
        int ctxChars = docs.stream().mapToInt(d -> d.getText().length()).sum();
        return new Probe(fragRanks, docs.size(), ctxChars, topK);
    }

    /** 与 KbQueryServiceImpl.resolveTopK 同构；配置从 Environment 读，与生产同一份 yml */
    private static int resolveTopK(String query) {
        int len = query.replaceAll("\\s+", "").length();
        Environment env = CTX_ENV;
        int shortLen = env.getProperty("app.ai.rag.search.short-query-length", Integer.class, 4);
        int longLen = env.getProperty("app.ai.rag.search.long-query-length", Integer.class, 16);
        if (len <= shortLen) return env.getProperty("app.ai.rag.search.topk-short", Integer.class, 20);
        if (len >= longLen) return env.getProperty("app.ai.rag.search.topk-long", Integer.class, 8);
        return env.getProperty("app.ai.rag.search.topk-medium", Integer.class, 12);
    }

    // ========== 指标与输出 ==========

    /** 题目级 HitRate：前 k 名里至少命中一个 gold 片段的题目占比（宽松口径） */
    private static double hitRate(List<Probe> probes, int k) {
        long hit = probes.stream().filter(p -> p.fragHitAt(k) > 0).count();
        return (double) hit / probes.size();
    }

    /** 片段级 Recall：前 k 名里命中的 gold 片段数 / 全部 gold 片段数（严格口径，多跳题不再被半截命中蒙混） */
    private static double recall(List<Probe> probes, int k) {
        long hit = probes.stream().mapToLong(p -> p.fragHitAt(k)).sum();
        long total = probes.stream().mapToLong(p -> p.fragRanks().size()).sum();
        return total == 0 ? 0 : (double) hit / total;
    }

    /** MRR@5：只取前 5 名。各组 topK 都 ≥8，@5 对每组都公平 */
    private static double mrr5(List<Probe> probes) {
        double sum = 0;
        for (Probe p : probes) {
            if (p.firstHitRank() > 0 && p.firstHitRank() <= 5) sum += 1.0 / p.firstHitRank();
        }
        return sum / probes.size();
    }

    private static String ablationTable(GoldSet gold, Map<String, List<Probe>> byGroup) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 四组消融对照 (N=").append(gold.cases.size())
                .append(", minScore 固定 ").append(FIXED_MIN_SCORE).append(") =====\n");
        sb.append("  Hit@K  = 题目级：前 K 名里至少命中一个 gold 片段的题目占比（宽松）\n");
        sb.append("  Rec@K  = 片段级：前 K 名里命中的 gold 片段数 / 全部 gold 片段数（严格，多跳题需全中）\n\n");
        sb.append("| 组 | 说明 | Hit@1 | Hit@3 | Hit@5 | Rec@5 | MRR@5 | 平均块数 | 平均上下文字符 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Group g : GROUPS) {
            List<Probe> ps = byGroup.get(g.key());
            sb.append(String.format("| %s | %s | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.3f | %.1f | %.0f |%n",
                    g.key(), g.label().trim(),
                    hitRate(ps, 1) * 100, hitRate(ps, 3) * 100, hitRate(ps, 5) * 100,
                    recall(ps, 5) * 100, mrr5(ps),
                    ps.stream().mapToInt(Probe::docCount).average().orElse(0),
                    ps.stream().mapToInt(Probe::ctxChars).average().orElse(0)));
        }
        return sb.toString();
    }

    /**
     * 按问题形态分层。这是本次评测最关键的切分：评估 Query Rewrite 要看「词汇不匹配」那层，
     * 评估动态 topK 要看「短查询」那层——在「规范」层上这两者本来就无从发挥。
     */
    private static String formTable(GoldSet gold, Map<String, List<Probe>> byGroup) {
        Map<String, List<Integer>> idxByForm = new LinkedHashMap<>();
        for (int i = 0; i < gold.cases.size(); i++) {
            String f = gold.cases.get(i).form == null ? "规范" : gold.cases.get(i).form;
            idxByForm.computeIfAbsent(f, k -> new ArrayList<>()).add(i);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 按问题形态分层：Hit@1 | Hit@5 | Rec@5 =====\n");
        sb.append("| 形态 | 题数 |");
        for (Group g : GROUPS) sb.append(" ").append(g.key()).append(" |");
        sb.append("\n|---|---|");
        for (Group g : GROUPS) sb.append("---|");
        sb.append('\n');
        for (var e : idxByForm.entrySet()) {
            sb.append(String.format("| %s | %d |", e.getKey(), e.getValue().size()));
            for (Group g : GROUPS) {
                List<Probe> all = byGroup.get(g.key());
                List<Probe> sub = e.getValue().stream().map(all::get).toList();
                sb.append(String.format(" %.1f%% / %.1f%% / %.1f%% |",
                        hitRate(sub, 1) * 100, hitRate(sub, 5) * 100, recall(sub, 5) * 100));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 各组实际落到哪个 topK 档——用来确认「短查询多取候选」这条路径真的被执行了 */
    private static String topkTierTable(GoldSet gold, Map<String, List<Probe>> byGroup) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 各组实际检索档位分布（题数） =====\n");
        sb.append("| 组 | 说明 |");
        for (int k : new int[]{8, 12, 20}) sb.append(" topK=").append(k).append(" |");
        sb.append("\n|---|---|---|----|----|\n");
        for (Group g : GROUPS) {
            Map<Integer, Long> cnt = new java.util.TreeMap<>();
            byGroup.get(g.key()).forEach(p -> cnt.merge(p.topK(), 1L, Long::sum));
            sb.append(String.format("| %s | %s |", g.key(), g.label().trim()));
            for (int k : new int[]{8, 12, 20}) sb.append(String.format(" %d |", cnt.getOrDefault(k, 0L)));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String typeStratifiedTable(GoldSet gold, Map<String, List<Probe>> byGroup) {
        Map<String, List<Integer>> idxByType = new LinkedHashMap<>();
        for (int i = 0; i < gold.cases.size(); i++) {
            idxByType.computeIfAbsent(gold.cases.get(i).type, t -> new ArrayList<>()).add(i);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 分层：Hit@5 / Rec@5（按问题类型） =====\n");
        sb.append("| 类型 | 题数 |");
        for (Group g : GROUPS) sb.append(" ").append(g.key()).append(" |");
        sb.append("\n|---|---|");
        for (Group g : GROUPS) sb.append("---|");
        sb.append('\n');
        for (var e : idxByType.entrySet()) {
            sb.append(String.format("| %s | %d |", e.getKey(), e.getValue().size()));
            for (Group g : GROUPS) {
                List<Probe> all = byGroup.get(g.key());
                List<Probe> sub = e.getValue().stream().map(all::get).toList();
                sb.append(String.format(" %.1f%% / %.1f%% |", hitRate(sub, 5) * 100, recall(sub, 5) * 100));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String minScoreTable(Map<Double, List<Probe>> byMinScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== minScore 敏感性（原问题 + topK8，只变 minScore） =====\n");
        sb.append("| minScore | Hit@1 | Hit@3 | Hit@5 | Rec@5 | MRR@5 | 平均块数 | 平均上下文字符 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (var e : byMinScore.entrySet()) {
            List<Probe> ps = e.getValue();
            sb.append(String.format("| %.2f | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.3f | %.1f | %.0f |%n",
                    e.getKey(), hitRate(ps, 1) * 100, hitRate(ps, 3) * 100, hitRate(ps, 5) * 100,
                    recall(ps, 5) * 100, mrr5(ps),
                    ps.stream().mapToInt(Probe::docCount).average().orElse(0),
                    ps.stream().mapToInt(Probe::ctxChars).average().orElse(0)));
        }
        return sb.toString();
    }

    /** 未命中明细：列出 A 组（基线）里没能全部命中的题，便于定位是检索失败还是标注问题 */
    private static String missDetail(GoldSet gold, Map<String, List<Probe>> byGroup) {
        List<Probe> base = byGroup.get("A");
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 未命中明细（A 组基线，列出 Rec@5 未满分的题） =====\n");
        int n = 0;
        for (int i = 0; i < gold.cases.size(); i++) {
            Probe p = base.get(i);
            if (p.fragHitAt(5) == p.fragRanks().size()) continue;
            n++;
            GoldCase c = gold.cases.get(i);
            sb.append(String.format("  [%2d] %s | %s%n", c.id, c.type, abbreviate(c.question)));
            sb.append(String.format("       命中排位(按片段顺序): %s  (0=前5名未找到，共%d个片段)%n",
                    p.fragRanks(), p.fragRanks().size()));
        }
        if (n == 0) sb.append("  无——A 组全部题目的 gold 片段都在前 5 名内命中\n");
        return sb.toString();
    }

    // ========== 工具 ==========

    private static Path resolveGoldPath() {
        for (String p : new String[]{"src/test/resources/eval/rag-gold.json",
                "backend/src/test/resources/eval/rag-gold.json"}) {
            Path path = Path.of(p);
            if (Files.exists(path)) return path;
        }
        throw new IllegalStateException(
                "找不到标注集，请从 backend/ 目录运行。期望路径: src/test/resources/eval/rag-gold.json");
    }

    /**
     * 归一化空白后再比对。PDF 解析出的正文在句子中间夹着大量排版硬换行
     * （如"这种对峙至\n少在表面上"），若要求标注逐字还原这些换行，标注集会非常脆弱；
     * 空白在此无语义，去掉不影响判定强度，却让 gold 片段可以按正常句子书写。
     */
    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 50 ? flat : flat.substring(0, 50) + "…";
    }
}
