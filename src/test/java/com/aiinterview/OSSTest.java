package com.aiinterview;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

public class OSSTest {
    public static void main(String[] args) {
        String accessKey = "LTAI5t5dFiwnqcoR6sGANmak";
        String secretKey = "D70OsfrYhJCSZQyJKO95VydMtp6Zj2";
        String endpoint = "https://oss-cn-beijing.aliyuncs.com";
        Region region = Region.of("oss-cn-beijing");

        S3Client s3 = S3Client.builder()
                .credentialsProvider(() -> AwsBasicCredentials.create(accessKey, secretKey))
                .endpointOverride(java.net.URI.create(endpoint))
                .region(region)
                .build();

        ListBucketsResponse response = s3.listBuckets();
        response.buckets().forEach(bucket -> System.out.println(bucket.name()));
    }
}