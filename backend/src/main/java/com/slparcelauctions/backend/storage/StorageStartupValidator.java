package com.slparcelauctions.backend.storage;

import java.util.Arrays;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Fails fast on startup in the {@code prod} profile if the configured bucket is
 * unreachable or nonexistent. Auto-creates the bucket in non-prod profiles so
 * {@code docker compose up} on a fresh MinIO volume just works.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StorageStartupValidator {

    private final S3Client s3;
    private final StorageConfigProperties props;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Storage bucket '{}' reachable", props.bucket());
        } catch (NoSuchBucketException e) {
            if (isProd) {
                throw new IllegalStateException(
                        "Storage bucket '" + props.bucket() + "' does not exist in prod profile. "
                                + "Provision via Terraform before deploying.", e);
            }
            log.warn("Storage bucket '{}' missing (non-prod profile); creating...", props.bucket());
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Storage bucket '{}' created", props.bucket());
        } catch (S3Exception e) {
            throw new IllegalStateException(
                    "Storage bucket '" + props.bucket() + "' is unreachable: " + e.getMessage(), e);
        }
    }
}
