package com.slparcelauctions.backend.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;

/**
 * Integration test for the {@link VerificationCodeService#consume} collision path
 * regression guard described in C1 of the Task 2 review.
 *
 * <p><strong>Why this test does NOT use class-level {@code @Transactional}:</strong>
 * The bug under test is that without {@code @Transactional(noRollbackFor = ...)} on
 * {@code consume}, Spring's transaction interceptor marks the surrounding tx for
 * rollback when {@link CodeCollisionException} propagates, silently reverting the
 * "void both colliding rows" writes. If this test wrapped each method in a Spring
 * test {@code @Transactional}, then {@code consume}'s {@code @Transactional} would
 * join the outer test tx (propagation REQUIRED), the writes would stay in the same
 * persistence context as the verification reads, and the test would pass even
 * with the bug present (because the L1 cache returns the dirty entities regardless
 * of rollback-only state).
 *
 * <p>To honestly demonstrate red→green, this test commits its setup in one tx,
 * calls {@code consume} (which runs in its own tx and either commits the void
 * writes or rolls them back depending on {@code noRollbackFor}), and then reads
 * the rows in a fresh tx. Cleanup happens manually in {@code @AfterEach}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class VerificationCodeServiceCollisionIntegrationTest {

    @Autowired VerificationCodeService verificationCodeService;
    @Autowired VerificationCodeRepository verificationCodeRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private final List<Long> codeIdsToCleanup = new ArrayList<>();
    private final List<Long> userIdsToCleanup = new ArrayList<>();

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            verificationCodeRepository.deleteAllById(codeIdsToCleanup);
            userRepository.deleteAllById(userIdsToCleanup);
        });
        codeIdsToCleanup.clear();
        userIdsToCleanup.clear();
    }

    @Test
    void consume_collisionBothRowsArePersistentlyVoidedDespiteException() {
        // Setup: commit two users + two verification rows sharing the same code
        // in one tx, so the writes are durable before we call consume.
        long[] ids = transactionTemplate.execute(status -> {
            User u1 = userRepository.save(User.builder()
                    .email("collision1@example.com").username("collision1")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Collision User 1")
                    .verified(false)
                    .build());
            User u2 = userRepository.save(User.builder()
                    .email("collision2@example.com").username("collision2")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Collision User 2")
                    .verified(false)
                    .build());
            userIdsToCleanup.add(u1.getId());
            userIdsToCleanup.add(u2.getId());

            OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(15);
            VerificationCode c1 = verificationCodeRepository.save(
                    VerificationCode.builder()
                            .userId(u1.getId())
                            .code("000000")
                            .type(VerificationCodeType.PLAYER)
                            .expiresAt(expiresAt)
                            .used(false)
                            .build());
            VerificationCode c2 = verificationCodeRepository.save(
                    VerificationCode.builder()
                            .userId(u2.getId())
                            .code("000000")
                            .type(VerificationCodeType.PLAYER)
                            .expiresAt(expiresAt)
                            .used(false)
                            .build());
            codeIdsToCleanup.add(c1.getId());
            codeIdsToCleanup.add(c2.getId());
            return new long[] { c1.getId(), c2.getId() };
        });

        // Act: consume runs in its own tx. With noRollbackFor on consume, the
        // void writes commit. Without it, Spring marks the tx rollback-only when
        // CodeCollisionException propagates and the writes silently revert.
        assertThatThrownBy(() ->
                verificationCodeService.consume("000000", VerificationCodeType.PLAYER))
                .isInstanceOf(CodeCollisionException.class);

        // Assert: re-read both rows in a FRESH tx and confirm both are voided.
        // This is the moment of truth - if noRollbackFor is missing, both rows
        // here will still have used=false because the void writes were rolled back.
        transactionTemplate.executeWithoutResult(status -> {
            VerificationCode reloaded1 = verificationCodeRepository.findById(ids[0]).orElseThrow();
            VerificationCode reloaded2 = verificationCodeRepository.findById(ids[1]).orElseThrow();
            assertThat(reloaded1.isUsed())
                    .as("first colliding row must be persistently voided despite the thrown exception")
                    .isTrue();
            assertThat(reloaded2.isUsed())
                    .as("second colliding row must be persistently voided despite the thrown exception")
                    .isTrue();
        });
    }
}
