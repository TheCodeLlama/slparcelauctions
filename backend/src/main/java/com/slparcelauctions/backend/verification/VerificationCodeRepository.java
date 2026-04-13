package com.slparcelauctions.backend.verification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VerificationCodeRepository
        extends JpaRepository<VerificationCode, Long> {

    /**
     * Returns ALL matching rows because the collision-detection path
     * (spec Q5b) needs to distinguish "exactly one match" from "more than one match."
     */
    List<VerificationCode> findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
            String code, VerificationCodeType type, OffsetDateTime now);

    /** Hydrates the dashboard via {@code GET /api/v1/verification/active}. */
    Optional<VerificationCode> findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, VerificationCodeType type, OffsetDateTime now);

    /** Returns every active row for a user so generate() can void them all. */
    List<VerificationCode> findByUserIdAndTypeAndUsedFalse(
            Long userId, VerificationCodeType type);
}
