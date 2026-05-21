package com.slparcelauctions.backend.coupon.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of {@code GET /api/v1/me/listings/prospective-discounts}. Wraps
 * the resolver's {@code DiscountSnapshot} with the public-id + code of
 * any coupon grant currently winning either the listing-fee or commission
 * target so the create-listing summary card can attribute the discount
 * without an extra round-trip.
 *
 * <p>Nulls signal "no coupon applies, default rate/fee is in effect" on
 * a per-target basis. The two targets resolve independently.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-20-coupon-codes-design.md}.
 */
public record ProspectiveDiscountsDto(
        long listingFeeLindens,
        BigDecimal commissionRate,
        UUID listingFeeCouponPublicId,
        String listingFeeCouponCode,
        UUID commissionCouponPublicId,
        String commissionCouponCode
) {}
