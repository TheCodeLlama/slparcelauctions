package com.slparcelauctions.backend.coupon;

/**
 * Which dimension of an auction a discount line applies to. Stored as
 * the {@code target} column on {@code coupon_discounts}.
 */
public enum DiscountTarget {
    LISTING_FEE,
    COMMISSION_RATE
}
