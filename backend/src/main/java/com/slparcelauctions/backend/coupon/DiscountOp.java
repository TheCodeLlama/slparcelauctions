package com.slparcelauctions.backend.coupon;

/**
 * How a discount line transforms the configured default. Stored as the
 * {@code op} column on {@code coupon_discounts}.
 *
 * <ul>
 *   <li>{@link #OVERRIDE} - replace the default with {@code value}.</li>
 *   <li>{@link #PERCENT_OFF} - subtract {@code value}% of the default.</li>
 *   <li>{@link #FLAT_OFF} - subtract {@code value} from the default,
 *       clamped to zero.</li>
 * </ul>
 *
 * Extend by adding a constant + a branch in
 * {@code CouponDiscountCalculator}; no schema migration required.
 */
public enum DiscountOp {
    OVERRIDE,
    PERCENT_OFF,
    FLAT_OFF
}
