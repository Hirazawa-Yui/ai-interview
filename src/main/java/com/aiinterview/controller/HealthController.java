package com.aiinterview.controller;

import com.aiinterview.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口
 */
@Tag(name = "系统健康", description = "应用存活检查")
@Slf4j
@RestController
public class HealthController {

    @Operation(summary = "健康检查", description = "验证应用是否正常启动，返回运行状态")
    @GetMapping("/api/health")
    public Result<String> health() {
        log.info("健康检查");
        return Result.ok("AI-Interview 平台运行正常");
    }
}
