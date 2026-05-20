package com.slparcelauctions.backend.coupon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin create-coupon request payload. The service further validates
 * cross-field constraints (LIFETIME_REQUIRED, SIGNUP_WINDOW_PAIRED,
 * duplicate code) before persisting.
 */
public record CreateCouponRequest(
        @NotBlank @Size(max = 64) String code,
        String description,
        Integer durationDays,
        Integer useCount,
        OffsetDateTime redeemableUntil,
        Integer maxTotalRedemptions,
        @Min(1) Integer maxPerUser,
        LocalDate signupWindowStart,
        LocalDate signupWindowEnd,
        Boolean active,
        Boolean notifyOnGrant,
        @NotEmpty List<CouponDiscountDto> discounts,
        List<UUID> allowedUserPublicIds
) {}
