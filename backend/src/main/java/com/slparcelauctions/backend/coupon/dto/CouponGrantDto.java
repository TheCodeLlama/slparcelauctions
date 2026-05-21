package com.slparcelauctions.backend.coupon.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.coupon.CouponGrantSource;
import com.slparcelauctions.backend.coupon.CouponGrantState;

/**
 * Wire shape for a {@link com.slparcelauctions.backend.coupon.CouponGrant}.
 * Carries the parent coupon's {@code publicId} and code so the wallet UI
 * can render the row without an extra round-trip, plus the flattened
 * discount bundle for the create-listing summary card.
 */
public record CouponGrantDto(
        UUID publicId,
        UUID couponPublicId,
        String code,
        OffsetDateTime grantedAt,
        OffsetDateTime expiresAt,
        Integer remainingCount,
        CouponGrantState state,
        CouponGrantSource source,
        List<CouponDiscountDto> discounts
) {}
