package com.slparcelauctions.backend.realty.moderation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RealtyGroupSuspension}.
 *
 * <p>Sub-project F spec §8.
 */
public interface RealtyGroupSuspensionRepository extends JpaRepository<RealtyGroupSuspension, Long> {

    /**
     * Currently-active suspension for the given group, if any. "Active" means: not lifted,
     * and either permanent ({@code expiresAt IS NULL}) or not yet expired at {@code :now}.
     * Used by {@code RealtyGroupGuard} to short-circuit operations on suspended groups.
     */
    @Query("""
        SELECT s FROM RealtyGroupSuspension s
         WHERE s.realtyGroup.id = :groupId
           AND s.liftedAt IS NULL
           AND (s.expiresAt IS NULL OR s.expiresAt > :now)
    """)
    Optional<RealtyGroupSuspension> findActiveByGroupId(
            @Param("groupId") Long groupId,
            @Param("now") OffsetDateTime now);

    /**
     * Full suspension history for a group, newest first. Drives the admin moderation page.
     *
     * <p>Eagerly fetches the {@code issuedByAdmin} + {@code liftedByAdmin} {@link
     * com.slparcelauctions.backend.user.User} associations so the controller's mapper
     * step can read each admin's {@code publicId} / {@code displayName} outside the
     * service transaction without tripping a {@link org.hibernate.LazyInitializationException}.
     * {@code open-in-view} is {@code false}, so any lazy proxy left on a returned entity
     * blows up at render time the moment the mapper touches it.
     */
    @Query("""
        SELECT s FROM RealtyGroupSuspension s
        LEFT JOIN FETCH s.issuedByAdmin
        LEFT JOIN FETCH s.liftedByAdmin
         WHERE s.realtyGroup.id = :groupId
         ORDER BY s.issuedAt DESC
    """)
    List<RealtyGroupSuspension> findHistoryByGroupId(@Param("groupId") Long groupId);

    /**
     * Timed suspensions whose expiry has passed but {@code liftedAt} has not been written.
     * Consumed by {@code GroupSuspensionExpiryTask} to auto-lift them.
     */
    @Query("""
        SELECT s FROM RealtyGroupSuspension s
         WHERE s.liftedAt IS NULL
           AND s.expiresAt IS NOT NULL
           AND s.expiresAt < :now
    """)
    List<RealtyGroupSuspension> findExpired(@Param("now") OffsetDateTime now);

    Optional<RealtyGroupSuspension> findByPublicId(UUID publicId);

    /**
     * Sub-project G §7.3 -- boolean form of {@link #findActiveByGroupId} used by
     * {@code RealtyGroupWalletService.withdraw}'s {@code SL_GROUP} branch. "Active"
     * means: not lifted, and either permanent ({@code expiresAt IS NULL}) or not
     * yet expired at {@code :now}.
     */
    @Query("""
        SELECT COUNT(s) > 0 FROM RealtyGroupSuspension s
         WHERE s.realtyGroup.id = :groupId
           AND s.liftedAt IS NULL
           AND (s.expiresAt IS NULL OR s.expiresAt > :now)
    """)
    boolean existsActiveForGroup(
            @Param("groupId") Long groupId,
            @Param("now") OffsetDateTime now);
}
