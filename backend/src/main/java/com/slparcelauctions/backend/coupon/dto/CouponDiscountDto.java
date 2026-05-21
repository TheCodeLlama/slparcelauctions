package com.slparcelauctions.backend.coupon.dto;

import com.slparcelauctions.backend.coupon.DiscountOp;
import com.slparcelauctions.backend.coupon.DiscountTarget;
import java.math.BigDecimal;

/**
 * One discount line on a coupon's bundle. Mirrors the
 * {@code coupon_discounts} row shape; {@code sortOrder} is optional on
 * creation (the service assigns ascending order when omitted).
 */
public record CouponDiscountDto(
        DiscountTarget target,
        DiscountOp op,
        BigDecimal value,
        Integer sortOrder
) {}
