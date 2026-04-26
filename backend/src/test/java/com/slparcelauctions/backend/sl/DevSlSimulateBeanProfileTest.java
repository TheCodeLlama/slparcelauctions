package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

/**
 * Proves {@code @Profile("dev")} gates {@link DevSlSimulateController}: loads the
 * context under the {@code test} profile (not {@code dev}), injects the controller
 * field with {@code required=false}, and asserts it's null.
 *
 * <p>Deliberately does NOT use {@code @ActiveProfiles("prod")} - the prod profile
 * requires {@code JWT_SECRET} and fails fast on empty {@code slpa.sl.trusted-owner-keys},
 * neither of which is available in a unit test environment. The {@code test} profile
 * is sufficient to prove the gate is working: it isn't {@code dev}, so the bean
 * must not be registered.
 *
 * <p>The {@link S3Client} is mocked via {@link MockitoBean} so
 * {@code StorageStartupValidator}'s {@code headBucket} call succeeds without
 * requiring a running MinIO container. This keeps the profile-gating test
 * hermetic - its only job is to prove the {@code @Profile} gate, not to
 * exercise real S3 infrastructure.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        // Satisfy the bean dependencies that would otherwise fail in a non-dev profile.
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtMTI=",
        "slpa.sl.trusted-owner-keys[0]=00000000-0000-0000-0000-000000000001",
        // EscrowStartupValidator is @Profile("!dev") and fails fast on non-dev
        // profiles when the secret is unset or shorter than 16 chars.
        "slpa.escrow.terminal-shared-secret=test-escrow-secret-at-least-sixteen-chars",
        // BotStartupValidator (Epic 06 Task 3) is @Profile("!dev") and fails
        // fast on blank / dev-placeholder / sub-16-char secrets and on the
        // dev-placeholder escrow UUID. Supply production-shaped values so the
        // non-dev profile boots.
        "slpa.bot.shared-secret=test-bot-shared-secret-at-least-sixteen-chars",
        "slpa.bot-task.primary-escrow-uuid=11111111-2222-3333-4444-555555555555",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/slpa",
        "spring.datasource.username=slpa",
        "spring.datasource.password=slpa",
        "spring.data.redis.host=localhost",
        "spring.jpa.hibernate.ddl-auto=update",
        "slpa.notifications.cleanup.enabled=false"
})
class DevSlSimulateBeanProfileTest {

    @Autowired(required = false)
    DevSlSimulateController controller;

    @MockitoBean
    S3Client s3Client;

    @BeforeEach
    void stubHeadBucket() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
    }

    @Test
    void controllerBeanIsNotRegisteredOutsideDevProfile() {
        assertThat(controller)
                .as("DevSlSimulateController must only be wired under @Profile(\"dev\")")
                .isNull();
    }
}
