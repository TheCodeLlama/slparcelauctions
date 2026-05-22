package com.slparcelauctions.backend.escrow;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Commission + payout math for auction-escrow payouts (spec section 4.3). The
 * L$50 floor means auctions closing under L$1,000 pay a disproportionate
 * commission - intentional per DESIGN.md to discourage micro-auctions.
 *
 * <p>The commission rate is NOT a constant here: it is the per-auction
 * {@code Auction.commissionRate} snapshot, taken at listing creation from
 * {@code slpa.commission.default-rate} and possibly lowered by a coupon. The
 * caller passes that snapshot in so a coupon-discounted rate is honored at
 * payout. The floor comes from {@code slpa.commission.minimum-lindens}.
 */
@Component
public class EscrowCommissionCalculator {

    private final long floorLindens;

    public EscrowCommissionCalculator(
            @Value("${slpa.commission.minimum-lindens}") long floorLindens) {
        this.floorLindens = floorLindens;
    }

    /**
     * Commission owed on a closed auction.
     *
     * @param finalBidAmount the winning bid in L$
     * @param commissionRate the per-auction commission rate snapshot
     *                        (e.g. {@code 0.0500} for 5%)
     */
    public long commission(long finalBidAmount, BigDecimal commissionRate) {
        long raw = BigDecimal.valueOf(finalBidAmount)
                .multiply(commissionRate)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        long afterFloor = Math.max(raw, floorLindens);
        return Math.min(afterFloor, finalBidAmount);
    }

    /**
     * Seller payout on a closed auction: the winning bid less commission.
     *
     * @param finalBidAmount the winning bid in L$
     * @param commissionRate the per-auction commission rate snapshot
     */
    public long payout(long finalBidAmount, BigDecimal commissionRate) {
        return finalBidAmount - commission(finalBidAmount, commissionRate);
    }
}
