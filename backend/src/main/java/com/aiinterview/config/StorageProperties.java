package com.aiinterview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OSS 存储配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** OSS Endpoint，如 https://oss-cn-beijing.aliyuncs.com */
    private String endpoint;

    /** 阿里云 AccessKey ID */
    private String accessKey;

    /** 阿里云 AccessKey Secret */
    private String secretKey;

    /** OSS Bucket 名称 */
    private String bucket;

    /** OSS 地域，如 oss-cn-beijing */
    private String region;
}
