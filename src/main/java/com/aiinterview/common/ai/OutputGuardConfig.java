package com.aiinterview.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 输出护栏配置（第三层防御）。
 * <p>
 * 投降语正则模式从 resources/defense/output-guard-patterns.json 加载，
 * 此处仅保留开关控制，具体模式列表由 {@link DefensePatternLoader} 管理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.defense.output-guard")
public class OutputGuardConfig {

    /** 是否启用输出护栏 */
    private boolean enabled = true;
}
