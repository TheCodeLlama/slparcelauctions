package com.slparcelauctions.backend.coupon;

/**
 * Lifecycle state of a {@link CouponGrant}. Stored as the {@code state}
 * column on {@code coupon_grants}.
 *
 * <ul>
 *   <li>{@link #ACTIVE} - usable; resolver considers it.</li>
 *   <li>{@link #EXHAUSTED} - {@code remaining_count} reached zero on
 *       activation consumption.</li>
 *   <li>{@link #EXPIRED} - {@code expires_at} passed; transitioned by
 *       the hourly sweeper.</li>
 *   <li>{@link #REVOKED} - admin revoked the grant directly.</li>
 * </ul>
 */
public enum CouponGrantState {
    ACTIVE,
    EXHAUSTED,
    EXPIRED,
    REVOKED
}
