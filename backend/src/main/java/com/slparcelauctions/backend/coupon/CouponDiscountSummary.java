package com.slparcelauctions.backend.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Short human-readable summary of a {@link Coupon}'s discount bundle for
 * use in notification body copy (email, in-app, SL IM). Per-line phrasing
 * follows the SLParcels transactional voice (no em-dashes, no marketing
 * adjectives) and surfaces the lifetime axis (durationDays or useCount)
 * inline so the recipient sees both what the coupon does and how long it
 * lasts.
 *
 * <p>Multi-line bundles are joined with ", " so a coupon with both a
 * listing-fee waiver and a commission cut reads as "Free listings,
 * Zero commission for 30 days".
 *
 * <p>Intentionally a static helper rather than a Spring bean -- pure
 * formatting with no DB / config dependencies.
 */
public final class CouponDiscountSummary {

    private CouponDiscountSummary() {}

    public static String describe(Coupon c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.getDiscounts().size(); i++) {
            if (i > 0) sb.append(", ");
            CouponDiscount d = c.getDiscounts().get(i);
            sb.append(describeLine(d, c));
        }
        return sb.toString();
    }

    private static String describeLine(CouponDiscount d, Coupon c) {
        String discountPart = switch (d.getOp()) {
            case OVERRIDE -> switch (d.getTarget()) {
                case LISTING_FEE -> d.getValue().compareTo(BigDecimal.ZERO) == 0
                        ? "Free listings"
                        : "L$" + d.getValue().toPlainString() + " listings";
                case COMMISSION_RATE -> d.getValue().compareTo(BigDecimal.ZERO) == 0
                        ? "Zero commission"
                        : d.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString()
                                + "% commission";
            };
            case PERCENT_OFF -> switch (d.getTarget()) {
                case LISTING_FEE -> d.getValue().toPlainString() + "% off listing fees";
                case COMMISSION_RATE -> d.getValue().toPlainString() + "% off commission";
            };
            case FLAT_OFF -> switch (d.getTarget()) {
                case LISTING_FEE -> "L$" + d.getValue().toPlainString() + " off listing fee";
                case COMMISSION_RATE -> d.getValue().toPlainString()
                        + " percentage points off commission";
            };
        };
        String suffix = "";
        if (c.getDurationDays() != null) {
            suffix = " for " + c.getDurationDays() + " days";
        } else if (c.getUseCount() != null) {
            suffix = " for next " + c.getUseCount() + " listing"
                    + (c.getUseCount() == 1 ? "" : "s");
        }
        return discountPart + suffix;
    }
}
