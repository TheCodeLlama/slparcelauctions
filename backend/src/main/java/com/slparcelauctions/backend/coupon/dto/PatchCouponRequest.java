package com.slparcelauctions.backend.coupon.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin patch-coupon request payload. Every field is nullable - null
 * means "leave alone"; an explicit value means "set to this". The
 * service rejects {@code durationDays}, {@code useCount}, and
 * {@code maxPerUser} when any grants already exist (IMMUTABLE_FIELD).
 */
public record PatchCouponRequest(
        String description,
        Boolean active,
        Boolean notifyOnGrant,
        OffsetDateTime redeemableUntil,
        Integer maxTotalRedemptions,
        List<UUID> allowedUserPublicIds,
        Integer durationDays,
        Integer useCount,
        Integer maxPerUser
) {}
