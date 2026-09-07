package com.aiinterview;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

public class OSSTest {
    public static void main(String[] args) {
        String accessKey = System.getenv("OSS_ACCESS_KEY");
        String secretKey = System.getenv("OSS_SECRET_KEY");
        String endpoint = "https://oss-cn-beijing.aliyuncs.com";
        Region region = Region.of("oss-cn-beijing");
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            System.out.println("❌ 未配置 OSS_ACCESS_KEY/OSS_SECRET_KEY：请复制 .env.example 为 .env 填入 Key，并在 IDEA Run Configuration 用 EnvFile 插件注入");
            return;
        }

        S3Client s3 = S3Client.builder()
                .credentialsProvider(() -> AwsBasicCredentials.create(accessKey, secretKey))
                .endpointOverride(java.net.URI.create(endpoint))
                .region(region)
                .build();

        ListBucketsResponse response = s3.listBuckets();
        response.buckets().forEach(bucket -> System.out.println(bucket.name()));
    }
}