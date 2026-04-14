package com.slparcelauctions.backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for S3-compatible object storage (MinIO in dev, real AWS S3 in prod).
 * Bound to {@code slpa.storage.*}.
 *
 * <p>The compact canonicalizing constructor supplies safe defaults: {@code region}
 * defaults to {@code "us-east-1"} if null/blank, {@code bucket} is required.
 */
@ConfigurationProperties(prefix = "slpa.storage")
public record StorageConfigProperties(
        String bucket,
        String region,
        String endpointOverride,
        boolean pathStyleAccess,
        String accessKeyId,
        String secretAccessKey
) {
    public StorageConfigProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("slpa.storage.bucket must be configured");
        }
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
    }

    public boolean hasStaticCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
            && secretAccessKey != null && !secretAccessKey.isBlank();
    }

    public boolean hasEndpointOverride() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }

    /**
     * Custom {@code toString()} that elides {@code accessKeyId} and
     * {@code secretAccessKey} so a stray {@code log.info("{}", props)} cannot
     * leak credentials. Reports {@link #hasStaticCredentials()} as a boolean
     * indicator instead.
     */
    @Override
    public String toString() {
        return "StorageConfigProperties[bucket=" + bucket
                + ", region=" + region
                + ", endpointOverride=" + endpointOverride
                + ", pathStyleAccess=" + pathStyleAccess
                + ", hasStaticCredentials=" + hasStaticCredentials()
                + "]";
    }
}
