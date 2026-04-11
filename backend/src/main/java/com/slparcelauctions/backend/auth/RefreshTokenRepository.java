package com.slparcelauctions.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByUserId(Long userId);

    /**
     * Single-query cascade: set {@code revoked_at = :now} on every currently-active refresh token
     * for the user. Used by (a) the reuse-detection cascade in {@code RefreshTokenService.rotate}
     * and (b) the {@code logout-all} endpoint. Returns the count of rows updated.
     *
     * <p>Atomic — don't loop in Java.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now "
         + "WHERE rt.userId = :userId AND rt.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /**
     * Scheduled cleanup: delete rows where the revocation OR expiry happened more than
     * {@code cutoff} ago. Retains 30 days of audit history via the call site's cutoff calculation.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE "
         + "(rt.revokedAt IS NOT NULL AND rt.revokedAt < :cutoff) OR "
         + "(rt.expiresAt < :cutoff)")
    int deleteOldRows(@Param("cutoff") OffsetDateTime cutoff);
}
