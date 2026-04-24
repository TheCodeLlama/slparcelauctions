package com.slparcelauctions.backend.user;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Server-computed completion rate for the listing-detail endpoint's
 * seller card. Keeps {@code cancelledWithBids} — a private seller
 * reliability signal — off the wire. Centralizes rounding + zero-
 * denominator handling in one place so DTO mappers do not re-derive
 * it inline.
 *
 * <p>Returns {@code null} for a brand-new seller (no completed sales
 * and no cancelled-with-bids), so the public API can render "—" rather
 * than a misleading "0%". Otherwise scales the result to two decimal
 * places using {@link RoundingMode#HALF_UP}.
 */
public final class SellerCompletionRateMapper {

    private SellerCompletionRateMapper() {}

    public static BigDecimal compute(int completedSales, int cancelledWithBids) {
        int denom = completedSales + cancelledWithBids;
        if (denom <= 0) {
            return null;
        }
        return BigDecimal.valueOf(completedSales)
                .divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
    }
}
