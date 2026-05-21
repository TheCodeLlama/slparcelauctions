package com.slparcelauctions.backend.coupon;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponRepository
        extends JpaRepository<Coupon, Long>, JpaSpecificationExecutor<Coupon> {

    /**
     * Case-insensitive lookup used by the redemption flow when the user
     * types a code. Backed by the {@code coupons_code_lower_idx}
     * functional index on {@code LOWER(code)}.
     */
    Optional<Coupon> findByCodeIgnoreCase(String code);

    Optional<Coupon> findByPublicId(UUID publicId);

    /**
     * Active coupons whose signup window contains {@code today} and
     * whose admin-set {@code redeemableUntil} has not yet passed.
     * Drives the signup-window auto-grant hook on user creation.
     */
    @Query("SELECT c FROM Coupon c WHERE c.active = true " +
           "AND c.signupWindowStart IS NOT NULL " +
           "AND c.signupWindowStart <= :today " +
           "AND c.signupWindowEnd >= :today " +
           "AND (c.redeemableUntil IS NULL OR c.redeemableUntil > CURRENT_TIMESTAMP)")
    List<Coupon> findActiveSignupWindowMatching(@Param("today") LocalDate today);
}
