package com.slparcelauctions.backend.coupon;

/**
 * Discriminator for {@link CouponException}. Each constant maps 1:1 to a
 * single rejection reason exposed at the API edge so the frontend can
 * render specific copy without parsing free-text messages.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-20-coupon-codes-design.md}.
 */
public enum CouponRedemptionError {
    UNKNOWN_CODE,
    NOT_ELIGIBLE,
    ALREADY_REDEEMED,
    EXPIRED,
    PAUSED,
    MAX_REACHED,
    INACTIVE,
    IMMUTABLE_FIELD,
    LIFETIME_REQUIRED,
    SIGNUP_WINDOW_PAIRED
}
