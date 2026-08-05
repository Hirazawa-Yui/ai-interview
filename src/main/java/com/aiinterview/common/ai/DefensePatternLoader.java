package com.aiinterview.common.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Prompt 注入防护 — 模式配置加载器。
 * <p>
 * 从 resources/defense/ 下的 JSON 文件加载正则模式和替换规则，
 * 编译为 {@link Pattern} 对象，实现配置与代码解耦。
 * <p>
 * 添加新模式只需编辑 JSON 文件，无需修改 Java 代码。
 */
@Slf4j
@Component
public class DefensePatternLoader {

    private final ObjectMapper objectMapper;

    /** 已编译的第一层正则模式 */
    @Getter
    private List<CompiledPattern> sanitizerPatterns = Collections.emptyList();

    /** 已编译的第三层输出护栏正则模式 */
    @Getter
    private List<CompiledPattern> guardPatterns = Collections.emptyList();

    public DefensePatternLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadPatterns() {
        sanitizerPatterns = load("defense/injection-patterns.json", "注入检测");
        guardPatterns = load("defense/output-guard-patterns.json", "输出护栏");
        log.info("防御模式加载完成: 注入检测={}条, 输出护栏={}条",
                sanitizerPatterns.size(), guardPatterns.size());
    }

    private List<CompiledPattern> load(String classpath, String category) {
        try {
            ClassPathResource resource = new ClassPathResource(classpath);
            if (!resource.exists()) {
                log.warn("防御配置文件不存在: {}，使用空配置", classpath);
                return Collections.emptyList();
            }

            try (InputStream in = resource.getInputStream()) {
                List<PatternEntry> entries = objectMapper.readValue(in,
                        new TypeReference<List<PatternEntry>>() {
                        });

                List<CompiledPattern> compiled = new ArrayList<>();
                for (PatternEntry entry : entries) {
                    try {
                        compiled.add(new CompiledPattern(
                                entry.getName(),
                                Pattern.compile(entry.getPattern(), Pattern.CASE_INSENSITIVE),
                                entry.getReplacement() != null ? entry.getReplacement() : "",
                                entry.getDescription()
                        ));
                    } catch (PatternSyntaxException e) {
                        log.error("{}正则语法错误 [{}]: {}", category, entry.getName(), e.getMessage());
                    }
                }
                log.info("加载{}模式: {}条 (共{}条定义)", category, compiled.size(), entries.size());
                return compiled;
            }
        } catch (Exception e) {
            log.error("加载{}配置文件失败: {}", category, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ============================================================
    // JSON 映射类
    // ============================================================

    @Data
    public static class PatternEntry {
        private String name;
        private String pattern;
        private String replacement;
        private String description;
    }

    /**
     * 已编译的正则模式（线程安全，可复用）
     */
    public record CompiledPattern(
            String name,
            Pattern pattern,
            String replacement,
            String description
    ) {
    }
}
