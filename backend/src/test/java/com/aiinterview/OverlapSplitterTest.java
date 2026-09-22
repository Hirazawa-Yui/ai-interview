package com.aiinterview;

import com.aiinterview.knowledge.splitter.OverlapTextSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字符制重叠分块器 — 单元测试（T19）
 *
 * <p>不需要 Spring 上下文：分块器是无依赖的纯算法类，与 KbVectorServiceImpl 里一样直接
 * 用 {@code split(List.of(new Document(text)))} 走生产同一条路径。
 */
class OverlapSplitterTest {

    private static final int SIZE = 500;
    private static final int OVERLAP = 80;

    private List<String> split(String text) {
        return new OverlapTextSplitter(SIZE, OVERLAP)
                .split(List.of(new Document(text)))
                .stream().map(Document::getText).toList();
    }

    /** 构造一段中文长文：每句约 30 字，共 count 句 */
    private String longText(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("这是第").append(i).append("句用于测试分块边界的示例文本内容需要足够长一些。");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("每块都不超过 chunkSize")
    void chunkSizeRespected() {
        for (String chunk : split(longText(200))) {
            assertTrue(chunk.length() <= SIZE, "块长度 " + chunk.length() + " 超过 " + SIZE);
        }
    }

    @Test
    @DisplayName("相邻块之间有重叠，且重叠量不小于 overlap")
    void adjacentChunksOverlap() {
        List<String> chunks = split(longText(200));
        assertTrue(chunks.size() > 3, "长文应切出多块，实际 " + chunks.size());

        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String cur = chunks.get(i);
            int common = commonPrefixUnderSuffixOf(prev, cur);
            int expected = Math.min(OVERLAP, prev.length());
            assertTrue(common >= expected,
                    "第 " + i + " 块与上一块重叠 " + common + " 字符，少于期望 " + expected);
        }
    }

    @Test
    @DisplayName("重叠区对齐句首，不从半句中间切")
    void overlapStartsAtSentenceBoundary() {
        List<String> chunks = split(longText(200));
        for (int i = 1; i < chunks.size(); i++) {
            char first = chunks.get(i).charAt(0);
            // 每块都必须以"上一句的结尾标点之后"开始，即不能是标点本身（句首不是标点）
            assertFalse(first == '。' || first == '！' || first == '？' || first == '；',
                    "第 " + i + " 块以标点开头，说明切在了句子边界之后：" + chunks.get(i).substring(0, 10));
        }
    }

    @Test
    @DisplayName("不丢字：每块都是原文的连续子串，首块从头开始、末块到结尾结束")
    void noTextLost() {
        String text = longText(200);
        List<String> chunks = split(text);

        for (String chunk : chunks) {
            assertTrue(text.contains(chunk), "出现原文中不存在的块：" + chunk.substring(0, Math.min(20, chunk.length())));
        }
        assertTrue(text.startsWith(chunks.get(0)), "首块必须是原文前缀");
        assertTrue(text.endsWith(chunks.get(chunks.size() - 1)), "末块必须是原文后缀");
    }

    @Test
    @DisplayName("每块都带来新内容：不允许出现完全落在上一块重叠区里的重复尾块")
    void noDuplicateTailChunks() {
        for (int sentences : new int[]{15, 16, 17, 18, 30, 31, 200}) {
            List<String> chunks = split(longText(sentences));
            for (int i = 1; i < chunks.size(); i++) {
                assertFalse(chunks.get(i - 1).contains(chunks.get(i)),
                        sentences + " 句文本：第 " + i + " 块完全被上一块包含（重复尾块）");
            }
        }
    }

    @Test
    @DisplayName("短文本只切一块，不做无谓拆分")
    void shortTextStaysWhole() {
        String text = "这是一句很短的话。";
        List<String> chunks = split(text);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    @DisplayName("超长单句（整段无句末标点）被硬切且不超限")
    void oversizedSentenceIsHardCut() {
        String text = "啊".repeat(SIZE * 3 + 37);   // 无任何句末标点
        List<String> chunks = split(text);
        assertEquals(4, chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= SIZE);
        }
        assertEquals(text, String.join("", chunks), "硬切片段拼回来应等于原文（硬切部分无重叠）");
    }

    @Test
    @DisplayName("空文本、纯空白不产生块；超长文本能终止且无空块")
    void edgeCases() {
        assertTrue(split("").isEmpty());
        assertTrue(split("   \n  ").isEmpty());

        List<String> chunks = split(longText(1000));   // ≈3 万字，验证不卡死
        assertTrue(chunks.size() > 10);
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty(), "出现空块");
        }
    }

    @Test
    @DisplayName("overlap 为 0 时退化为不重叠切分")
    void zeroOverlap() {
        List<String> chunks = new OverlapTextSplitter(SIZE, 0)
                .split(List.of(new Document(longText(200))))
                .stream().map(Document::getText).toList();
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(0, commonPrefixUnderSuffixOf(chunks.get(i - 1), chunks.get(i)),
                    "关闭重叠后相邻块不应有公共衔接");
        }
    }

    /** 求 cur 的前缀同时是 prev 后缀的最大长度（即真实重叠字符数） */
    private int commonPrefixUnderSuffixOf(String prev, String cur) {
        int max = Math.min(prev.length(), cur.length());
        for (int len = max; len > 0; len--) {
            if (prev.regionMatches(prev.length() - len, cur, 0, len)) {
                return len;
            }
        }
        return 0;
    }
}
