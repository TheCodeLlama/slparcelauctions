package com.slparcelauctions.backend.coupon.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin-facing coupon detail DTO. Carries the full coupon template plus
 * its embedded discount bundle and per-user allowlist as
 * {@code publicId}s. Internal {@code id} stays server-side.
 */
public record CouponDto(
        UUID publicId,
        String code,
        String description,
        Integer durationDays,
        Integer useCount,
        OffsetDateTime redeemableUntil,
        Integer maxTotalRedemptions,
        Integer maxPerUser,
        LocalDate signupWindowStart,
        LocalDate signupWindowEnd,
        boolean active,
        boolean notifyOnGrant,
        List<CouponDiscountDto> discounts,
        List<UUID> allowedUserPublicIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
