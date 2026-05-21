package com.slparcelauctions.backend.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure computational primitive for applying a single coupon discount line.
 *
 * <p>Stateless. No Spring beans, no DB, no I/O. Called by the coupon
 * resolver once per {@code (target, op, value)} discount row to produce
 * the resulting listing fee (lindens) or commission rate (e.g. 0.05).
 *
 * <p>Semantics follow the coupon-codes design spec section 2:
 * <ul>
 *   <li>Listing fee: all ops work in lindens directly. {@code OVERRIDE}
 *       interprets {@code value} as lindens; {@code PERCENT_OFF}
 *       subtracts {@code value}% of the default; {@code FLAT_OFF}
 *       subtracts {@code value} lindens.</li>
 *   <li>Commission: {@code OVERRIDE} interprets {@code value} as a
 *       percent (3.0 means 3%, returned as rate 0.03); {@code PERCENT_OFF}
 *       reduces the default rate by {@code value}% of itself;
 *       {@code FLAT_OFF} subtracts {@code value} percentage points from
 *       the default rate.</li>
 * </ul>
 *
 * <p>Both functions clamp the result at zero; neither ever returns a
 * negative value.
 */
public final class CouponDiscountCalculator {

    private CouponDiscountCalculator() {}

    /** Default fee in L$ (integer lindens); returns the resulting fee in lindens, never negative. */
    public static long applyListingFee(DiscountOp op, BigDecimal value, long defaultFee) {
        return switch (op) {
            case OVERRIDE -> Math.max(0L, value.longValueExact());
            case PERCENT_OFF -> {
                BigDecimal pct = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                BigDecimal multiplier = BigDecimal.ONE.subtract(pct);
                BigDecimal result = new BigDecimal(defaultFee).multiply(multiplier);
                yield Math.max(0L, result.setScale(0, RoundingMode.HALF_UP).longValueExact());
            }
            case FLAT_OFF -> Math.max(0L, defaultFee - value.longValueExact());
        };
    }

    /** Returns the resulting commission rate (e.g. 0.05 = 5%), never negative. */
    public static BigDecimal applyCommission(DiscountOp op, BigDecimal value, BigDecimal defaultRate) {
        BigDecimal result = switch (op) {
            case OVERRIDE -> value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
            case PERCENT_OFF -> {
                BigDecimal pct = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                yield defaultRate.multiply(BigDecimal.ONE.subtract(pct));
            }
            case FLAT_OFF -> {
                BigDecimal pts = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
                yield defaultRate.subtract(pts);
            }
        };
        if (result.signum() < 0) {
            result = BigDecimal.ZERO;
        }
        // Scale 4 = 4 decimal places = 1 basis point precision; needed so
        // a 50%-off-of-5% reduction yields 0.025 (2.5%) rather than rounding
        // to 0.03. isEqualByComparingTo ignores scale, so the higher scale
        // is still equivalent for scale-2 results like 0.05 or 0.03.
        return result.setScale(4, RoundingMode.HALF_UP);
    }
}
