package com.slparcelauctions.backend.coupon.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin list row. Carries enough to render the admin coupons table
 * without a per-row detail fetch: code, description, active flag,
 * expiry, the discount bundle, and grant counters.
 */
public record CouponSummaryDto(
        UUID publicId,
        String code,
        String description,
        boolean active,
        OffsetDateTime redeemableUntil,
        List<CouponDiscountDto> discounts,
        long totalGrants,
        long activeGrants,
        Integer maxTotalRedemptions
) {}
