package com.slparcelauctions.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.slparcelauctions.backend.admin.users.dto.UserIpProjection;

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

    /**
     * Returns one row per distinct IP address seen in this user's refresh token
     * history, with first/last seen timestamps and a session count. Used by the
     * admin user-detail "Recent IPs" modal.
     *
     * <p>Rows with a null {@code ip_address} are excluded (recorded before the
     * column was added, or from clients that don't send an IP).
     */
    @Query("""
        SELECT new com.slparcelauctions.backend.admin.users.dto.UserIpProjection(
            rt.ipAddress, MIN(rt.createdAt), MAX(rt.lastUsedAt), COUNT(rt.id))
        FROM RefreshToken rt
        WHERE rt.userId = :userId AND rt.ipAddress IS NOT NULL
        GROUP BY rt.ipAddress
        ORDER BY MIN(rt.createdAt) ASC
        """)
    List<UserIpProjection> findIpSummaryByUserId(@Param("userId") Long userId);
}
