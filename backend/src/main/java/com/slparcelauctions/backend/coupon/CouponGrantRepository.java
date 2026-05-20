package com.slparcelauctions.backend.coupon;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponGrantRepository extends JpaRepository<CouponGrant, Long> {

    /**
     * Hot path for listing-creation: load every active grant a user
     * holds, ordered FIFO by {@code grantedAt} so the resolver tiebreak
     * stays deterministic. Backed by the
     * {@code coupon_grants_user_state_idx} partial index.
     */
    List<CouponGrant> findByUserIdAndStateOrderByGrantedAtAsc(long userId, CouponGrantState state);

    /** Powers the wallet history view (active + past grants). */
    List<CouponGrant> findByUserIdOrderByGrantedAtDesc(long userId);

    Optional<CouponGrant> findByCouponIdAndUserId(long couponId, long userId);

    Optional<CouponGrant> findByPublicId(UUID publicId);

    long countByCouponId(long couponId);

    /**
     * Counts only ACTIVE grants for a coupon. Powers the admin list-row
     * summary's {@code activeGrants} counter alongside the unconditional
     * {@code totalGrants} from {@link #countByCouponId(long)}.
     */
    @Query("SELECT COUNT(g) FROM CouponGrant g WHERE g.coupon.id = :cid " +
           "AND g.state = com.slparcelauctions.backend.coupon.CouponGrantState.ACTIVE")
    long countByCouponIdAndStateActive(@Param("cid") long couponId);

    long countByCouponIdAndUserId(long couponId, long userId);

    Page<CouponGrant> findByCouponId(long couponId, Pageable pageable);

    /**
     * Hourly sweeper: flips ACTIVE grants past their {@code expiresAt}
     * to {@link CouponGrantState#EXPIRED}. Idempotent - re-running
     * mutates zero additional rows. Resolver also filters expired
     * grants in memory so sweeper lag never produces an incorrect
     * discount snapshot.
     */
    @Modifying
    @Query("UPDATE CouponGrant g SET g.state = " +
           "com.slparcelauctions.backend.coupon.CouponGrantState.EXPIRED, " +
           "g.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE g.state = com.slparcelauctions.backend.coupon.CouponGrantState.ACTIVE " +
           "AND g.expiresAt IS NOT NULL AND g.expiresAt < :now")
    int markExpired(@Param("now") OffsetDateTime now);
}
