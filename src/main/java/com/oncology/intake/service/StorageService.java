package com.oncology.intake.service;

import com.oncology.intake.config.StorageConfig;
import com.oncology.intake.exception.IntakeExceptions.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Service for storing and retrieving files (medical reports).
 * Supports local filesystem, AWS S3, and MinIO storage backends.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private final StorageConfig storageConfig;
    private final ObjectProvider<S3Client> s3ClientProvider;

    private S3Client s3ClientOrNull() {
        return s3ClientProvider.getIfAvailable();
    }

    @PostConstruct
    public void init() {
        if ("local".equalsIgnoreCase(storageConfig.getType())) {
            try {
                Path basePath = Paths.get(storageConfig.getLocal().getBasePath());
                Files.createDirectories(basePath);
                log.info("Local storage initialized at: {}", basePath.toAbsolutePath());
            } catch (IOException e) {
                throw new StorageException("Failed to initialize local storage", e);
            }
            return;
        }

        // s3 / minio
        S3Client s3 = s3ClientOrNull();
        if (s3 == null) {
            throw new StorageException(
                    "Storage type is '" + storageConfig.getType() + "' but no S3Client bean is configured. " +
                    "For local testing set storage.type=local, or configure an S3Client bean for s3/minio."
            );
        }

        try {
            s3.headBucket(HeadBucketRequest.builder()
                    .bucket(storageConfig.getS3().getBucketName())
                    .build());
            log.info("S3 storage initialized with bucket: {}", storageConfig.getS3().getBucketName());
        } catch (NoSuchBucketException e) {
            log.warn("S3 bucket does not exist: {}. Will attempt to create.", storageConfig.getS3().getBucketName());
            createBucket(s3);
        }
    }

    public StorageResult storeFile(byte[] content, String originalFileName,
                                  String contentType, UUID patientId) {
        String key = generateStorageKey(patientId, originalFileName);
        String checksum = calculateChecksum(content);

        switch (storageConfig.getType()) {
            case "local":
                return storeLocally(content, key, checksum);
            case "s3":
            case "minio":
                return storeInS3(requireS3Client(), content, key, contentType, checksum);
            default:
                throw new StorageException("Unknown storage type: " + storageConfig.getType());
        }
    }

    public StorageResult storeFile(InputStream inputStream, String originalFileName,
                                  String contentType, UUID patientId) {
        try {
            byte[] content = inputStream.readAllBytes();
            return storeFile(content, originalFileName, contentType, patientId);
        } catch (IOException e) {
            throw new StorageException("Failed to read input stream", e);
        }
    }

    public byte[] retrieveFile(String storageKey) {
        switch (storageConfig.getType()) {
            case "local":
                return retrieveLocally(storageKey);
            case "s3":
            case "minio":
                return retrieveFromS3(requireS3Client(), storageKey);
            default:
                throw new StorageException("Unknown storage type: " + storageConfig.getType());
        }
    }

    public void deleteFile(String storageKey) {
        switch (storageConfig.getType()) {
            case "local":
                deleteLocally(storageKey);
                break;
            case "s3":
            case "minio":
                deleteFromS3(requireS3Client(), storageKey);
                break;
            default:
                throw new StorageException("Unknown storage type: " + storageConfig.getType());
        }
    }

    public boolean fileExists(String storageKey) {
        switch (storageConfig.getType()) {
            case "local":
                return Files.exists(Paths.get(storageConfig.getLocal().getBasePath(), storageKey));
            case "s3":
            case "minio":
                try {
                    requireS3Client().headObject(HeadObjectRequest.builder()
                            .bucket(storageConfig.getS3().getBucketName())
                            .key(storageKey)
                            .build());
                    return true;
                } catch (NoSuchKeyException e) {
                    return false;
                }
            default:
                return false;
        }
    }

    // =============== Private Methods ===============

    private S3Client requireS3Client() {
        S3Client s3 = s3ClientOrNull();
        if (s3 == null) {
            throw new StorageException("S3Client is required for storage.type=" + storageConfig.getType());
        }
        return s3;
    }

    private StorageResult storeLocally(byte[] content, String key, String checksum) {
        try {
            Path filePath = Paths.get(storageConfig.getLocal().getBasePath(), key);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content);

            log.debug("File stored locally: {}", filePath);
            return new StorageResult(key, checksum, (long) content.length);
        } catch (IOException e) {
            throw new StorageException("Failed to store file locally", e);
        }
    }

    private StorageResult storeInS3(S3Client s3, byte[] content, String key,
                                   String contentType, String checksum) {
        try {
            // Always request AES256 server-side encryption. This is independent of
            // any bucket-level default — setting it on the request defends against
            // a misconfigured bucket. For envelope encryption with KMS, switch to
            // ServerSideEncryption.AWS_KMS and add .ssekmsKeyId(...).
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageConfig.getS3().getBucketName())
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build();

            s3.putObject(request, RequestBody.fromBytes(content));

            log.debug("File stored in S3: {}", key);
            return new StorageResult(key, checksum, (long) content.length);
        } catch (S3Exception e) {
            throw new StorageException("Failed to store file in S3: " + e.getMessage(), e);
        }
    }

    private byte[] retrieveLocally(String key) {
        try {
            Path filePath = Paths.get(storageConfig.getLocal().getBasePath(), key);
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new StorageException("Failed to retrieve file locally: " + key, e);
        }
    }

    private byte[] retrieveFromS3(S3Client s3, String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(storageConfig.getS3().getBucketName())
                    .key(key)
                    .build();

            return s3.getObjectAsBytes(request).asByteArray();
        } catch (S3Exception e) {
            throw new StorageException("Failed to retrieve file from S3: " + key, e);
        }
    }

    private void deleteLocally(String key) {
        try {
            Path filePath = Paths.get(storageConfig.getLocal().getBasePath(), key);
            Files.deleteIfExists(filePath);
            log.debug("File deleted locally: {}", filePath);
        } catch (IOException e) {
            throw new StorageException("Failed to delete file locally: " + key, e);
        }
    }

    private void deleteFromS3(S3Client s3, String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getS3().getBucketName())
                    .key(key)
                    .build();

            s3.deleteObject(request);
            log.debug("File deleted from S3: {}", key);
        } catch (S3Exception e) {
            throw new StorageException("Failed to delete file from S3: " + key, e);
        }
    }

    private void createBucket(S3Client s3) {
        try {
            s3.createBucket(CreateBucketRequest.builder()
                    .bucket(storageConfig.getS3().getBucketName())
                    .build());
            log.info("Created S3 bucket: {}", storageConfig.getS3().getBucketName());
        } catch (S3Exception e) {
            throw new StorageException("Failed to create S3 bucket", e);
        }
    }

    private String generateStorageKey(UUID patientId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return String.format("patients/%s/reports/%s%s", patientId, UUID.randomUUID(), extension);
    }

    private String calculateChecksum(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available, skipping checksum");
            return null;
        }
    }

    public record StorageResult(String storageKey, String checksum, Long sizeBytes) {}
}
