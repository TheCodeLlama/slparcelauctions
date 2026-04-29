package com.slparcelauctions.backend.user;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Server-computed completion rate for seller-card DTOs (listing-detail,
 * browse/search result, public profile). Keeps the two private
 * reliability counters — {@code cancelledWithBids} and
 * {@code escrowExpiredUnfulfilled} — off the wire and centralises
 * rounding + zero-denominator handling.
 *
 * <p>Epic 08 sub-spec 1 §3.5 widened the denominator from
 * {@code completed + cancelledWithBids} to
 * {@code completed + cancelledWithBids + escrowExpiredUnfulfilled} so a
 * seller who silently lets the 72h transfer window lapse is visibly
 * penalised alongside a seller who cancels an auction mid-flight. Both
 * failure modes are seller-attributed; buyer-side payment timeouts do not
 * increment {@code escrowExpiredUnfulfilled} and therefore never affect
 * the seller's rate.
 *
 * <p>Returns {@code null} for a brand-new seller (all three counters at
 * zero), so the public API can render "—" rather than a misleading
 * "0%". Otherwise scales the result to two decimal places using
 * {@link RoundingMode#HALF_UP}.
 */
public final class SellerCompletionRateMapper {

    private SellerCompletionRateMapper() {}

    public static BigDecimal compute(
            int completedSales,
            int cancelledWithBids,
            int escrowExpiredUnfulfilled) {
        int denom = completedSales + cancelledWithBids + escrowExpiredUnfulfilled;
        if (denom <= 0) {
            return null;
        }
        return BigDecimal.valueOf(completedSales)
                .divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
    }
}
