package com.slparcelauctions.backend.coupon;

/**
 * How a {@link CouponGrant} was created. Stored as the {@code source}
 * column on {@code coupon_grants}.
 *
 * <ul>
 *   <li>{@link #REDEMPTION} - user typed the code into the wallet
 *       redeem form. Silent (no notification).</li>
 *   <li>{@link #ADMIN_GRANT} - admin direct-granted from the coupon
 *       detail page. Notifies if the parent coupon's
 *       {@code notifyOnGrant} is true.</li>
 *   <li>{@link #SIGNUP_WINDOW} - auto-grant on user creation when the
 *       new user's signup date falls inside an active coupon's window,
 *       or backfill at coupon-save time for pre-existing users in the
 *       window. Notifies if the parent coupon's
 *       {@code notifyOnGrant} is true.</li>
 * </ul>
 */
public enum CouponGrantSource {
    REDEMPTION,
    ADMIN_GRANT,
    SIGNUP_WINDOW
}
