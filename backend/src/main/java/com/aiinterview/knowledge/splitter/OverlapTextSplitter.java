package com.aiinterview.knowledge.splitter;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 字符制重叠分块器（T19）。
 *
 * <p><b>为什么自研</b>：Spring AI 2.0.0-M4 的 {@code TokenTextSplitter} 的 builder 根本没有重叠参数
 * （实测其 Builder 只有 chunkSize/minChunkSizeChars/minChunkLengthToEmbed/maxNumChunks/keepSeparator/
 * punctuationMarks，无 overlap），而且它的 chunkSize 单位是 <b>token</b> 不是字符——中文场景下
 * 800 token ≈ 1000+ 字符，块偏大且相邻块之间零重叠、语义断裂。
 *
 * <p><b>规则</b>：
 * <ol>
 *   <li>先按句末标点（。！？；!?; 与换行）切成原子句，标点跟随前句；</li>
 *   <li>超过 chunkSize 的原子句先硬切成若干 chunkSize 片段（保证装箱环节每个原子都 ≤ chunkSize）；</li>
 *   <li>贪心装箱：累加原子句直到再加就超过 chunkSize 为止，成一块；</li>
 *   <li>重叠：下一块从"上一块尾部累计 ≥ overlap 字符的<b>整句</b>处"开始——<b>对齐句首而不是从半句中间切</b>，
 *       并保证每轮严格前进、不产生空块、不产生"整块重复"。</li>
 * </ol>
 *
 * <p>文本不丢字：每块都是原文的连续子串，相邻块的重复部分即重叠区。
 */
public class OverlapTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;

    /**
     * @param chunkSize 每块最大字符数
     * @param overlap   相邻块重叠字符数（实际取值可能略大于它——会对齐到整句；且不超过 chunkSize/2）
     */
    public OverlapTextSplitter(int chunkSize, int overlap) {
        this.chunkSize = Math.max(1, chunkSize);
        this.overlap = Math.max(0, Math.min(overlap, this.chunkSize / 2));
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        List<String> atoms = toAtoms(text);

        int i = 0;
        int prevEnd = 0;   // 上一块结束的原子下标（不含）
        while (i < atoms.size()) {
            // 贪心装箱：从第 i 个原子开始，能塞多少塞多少
            int len = 0;
            int j = i;
            while (j < atoms.size()) {
                int atomLen = atoms.get(j).length();
                if (j > i && len + atomLen > chunkSize) {
                    break;
                }
                len += atomLen;
                j++;
            }
            if (j <= prevEnd) {
                // 本块完全落在上一块的重叠区里（文末短尾时会出现）→ 没有新内容，收尾
                break;
            }
            chunks.add(join(atoms, i, j));
            prevEnd = j;

            // 下一块的起点：从块尾往回取整句，直到够 overlap（再多不超过 2 倍，避免吃掉整块）
            int tail = 0;
            int m = j;
            while (m > i && tail < overlap) {
                int candidate = tail + atoms.get(m - 1).length();
                if (tail > 0 && candidate > overlap * 2) {
                    break;
                }
                m--;
                tail = candidate;
            }
            i = Math.max(m, i + 1);  // 严格前进，杜绝死循环
        }
        return chunks;
    }

    /** 切原子句；超长句先硬切，保证返回的每个原子都 ≤ chunkSize */
    private List<String> toAtoms(String text) {
        List<String> atoms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isSentenceEnd(c)) {
                addAtom(atoms, current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            addAtom(atoms, current.toString());
        }
        return atoms;
    }

    private void addAtom(List<String> atoms, String atom) {
        if (atom.length() <= chunkSize) {
            atoms.add(atom);
            return;
        }
        for (int p = 0; p < atom.length(); p += chunkSize) {
            atoms.add(atom.substring(p, Math.min(p + chunkSize, atom.length())));
        }
    }

    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                || c == '!' || c == '?' || c == ';';
    }

    private String join(List<String> atoms, int from, int to) {
        if (to - from == 1) {
            return atoms.get(from);
        }
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < to; k++) {
            sb.append(atoms.get(k));
        }
        return sb.toString();
    }
}
