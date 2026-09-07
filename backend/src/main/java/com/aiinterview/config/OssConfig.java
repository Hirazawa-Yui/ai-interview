package com.aiinterview.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * OSS 客户端配置（使用 AWS S3 SDK 兼容模式访问阿里云 OSS）
 * <p>
 * 只有配置了 OSS AccessKey 后才生效（app.storage.enabled=true）。
 * 使用 Lambda 凭证提供器，与 OSS 签名兼容性最好（已验证通过）。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class OssConfig {

    private final StorageProperties storageConfig;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .credentialsProvider(() -> AwsBasicCredentials.create(
                        storageConfig.getAccessKey(),
                        storageConfig.getSecretKey()
                ))
                .endpointOverride(URI.create(storageConfig.getEndpoint()))
                .region(Region.of(storageConfig.getRegion()))
                .build();
    }
}
