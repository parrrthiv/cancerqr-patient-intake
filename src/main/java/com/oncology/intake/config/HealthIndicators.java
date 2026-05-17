package com.oncology.intake.config;

import com.oncology.intake.config.StorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

/**
 * Real healthcheck — exercises actual external dependencies instead of
 * just confirming the JVM is up.
 *
 * <p>Spring Boot Actuator's default {@code /actuator/health} aggregates every
 * {@link HealthIndicator} bean and returns DOWN if any are unhealthy. By
 * registering custom indicators for each real dependency we make the
 * endpoint a meaningful liveness signal instead of a JVM-is-alive ping.
 *
 * <h2>Indicators</h2>
 * <ul>
 *   <li><b>db</b>: Spring Boot already auto-registers a DataSource health
 *       indicator; no custom code needed. Lives at {@code /actuator/health/db}.</li>
 *   <li><b>s3</b>: this file. Calls {@code HeadBucket} with a short timeout.
 *       Reports DOWN with the underlying exception if S3 is unreachable, the
 *       IAM role is broken, or the bucket has disappeared.</li>
 * </ul>
 *
 * <h2>Why not also WhatsApp?</h2>
 * Pinging Meta's API on every healthcheck would (a) burn API quota, (b) tie
 * our uptime indicator to Meta's, and (c) consume a thread on every probe.
 * Webhook health is observable via the {@code webhook.received} Micrometer
 * counter and via the {@code webhook.signature_failed} / {@code .parse_failed} /
 * {@code .processing_failed} counters. That signal is more useful operationally
 * than a synthetic ping.
 */
@Configuration
@Slf4j
public class HealthIndicators {

    private static final Duration S3_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Verifies the application can reach the configured S3 bucket.
     *
     * <p>Returns UP only if {@code HeadBucket} succeeds within {@code S3_TIMEOUT}.
     * Reports DOWN otherwise with the bucket name and short error description —
     * useful for distinguishing "wrong bucket configured" from "S3 unreachable"
     * from "IAM permissions missing".
     */
    @Bean
    public HealthIndicator s3HealthIndicator(StorageConfig storageConfig,
                                             org.springframework.beans.factory.ObjectProvider<S3Client> s3ClientProvider) {
        return () -> {
            // Only check S3 if storage is actually using S3 (not local filesystem).
            if (!"s3".equalsIgnoreCase(storageConfig.getType())
                    && !"minio".equalsIgnoreCase(storageConfig.getType())) {
                return Health.up().withDetail("backend", storageConfig.getType()).build();
            }

            S3Client s3 = s3ClientProvider.getIfAvailable();
            if (s3 == null) {
                return Health.down()
                        .withDetail("reason", "S3Client bean not available")
                        .build();
            }

            String bucket = storageConfig.getS3().getBucketName();
            Instant start = Instant.now();
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                Duration elapsed = Duration.between(start, Instant.now());
                if (elapsed.compareTo(S3_TIMEOUT) > 0) {
                    return Health.down()
                            .withDetail("bucket", bucket)
                            .withDetail("reason", "S3 HeadBucket exceeded " + S3_TIMEOUT)
                            .withDetail("elapsedMs", elapsed.toMillis())
                            .build();
                }
                return Health.up()
                        .withDetail("bucket", bucket)
                        .withDetail("elapsedMs", elapsed.toMillis())
                        .build();
            } catch (Exception e) {
                return Health.down(e)
                        .withDetail("bucket", bucket)
                        .build();
            }
        };
    }
}
