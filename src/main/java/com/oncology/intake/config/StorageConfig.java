package com.oncology.intake.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Configuration for file storage (S3/MinIO/Local).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "storage")
public class StorageConfig {

    private String type = "local";  // local, s3, minio
    private LocalStorage local = new LocalStorage();
    private S3Storage s3 = new S3Storage();

    @Data
    public static class LocalStorage {
        private String basePath = "./uploads";
    }

    @Data
    public static class S3Storage {
        private String bucketName = "oncology-reports";
        private String region = "us-east-1";
        private String accessKey;
        private String secretKey;
        private String endpoint;  // For MinIO
    }

    /**
     * Create S3 client if using S3/MinIO storage
     */
    @Bean
    public S3Client s3Client() {
        if (!"s3".equals(type) && !"minio".equals(type)) {
            return null;
        }

        var builder = S3Client.builder()
                .region(Region.of(s3.getRegion()));

        // Configure credentials (fall back to IAM instance role if no keys provided)
        if (s3.getAccessKey() != null && !s3.getAccessKey().isBlank()
                && s3.getSecretKey() != null && !s3.getSecretKey().isBlank()) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())
                    )
            );
        }

        // Configure endpoint for MinIO
        if (s3.getEndpoint() != null && !s3.getEndpoint().isEmpty()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()))
                   .forcePathStyle(true);  // Required for MinIO
        }

        return builder.build();
    }
}
