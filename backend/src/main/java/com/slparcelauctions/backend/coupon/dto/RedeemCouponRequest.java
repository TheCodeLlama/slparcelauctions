package com.slparcelauctions.backend.coupon.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * User-typed redemption payload. The wallet redeem form posts a single
 * code; the service resolves it case-insensitively.
 */
public record RedeemCouponRequest(@NotBlank String code) {}
