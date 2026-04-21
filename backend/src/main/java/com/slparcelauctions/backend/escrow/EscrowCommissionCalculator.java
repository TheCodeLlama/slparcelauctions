package com.slparcelauctions.backend.escrow;

import org.springframework.stereotype.Component;

/**
 * Commission + payout math for auction-escrow payouts (spec §4.3). The L$50
 * floor means auctions closing under L$1,000 pay a disproportionate
 * commission — intentional per DESIGN.md to discourage micro-auctions.
 */
@Component
public class EscrowCommissionCalculator {

    private static final long FLOOR_LINDENS = 50L;
    private static final long RATE_PERCENT = 5L;

    public long commission(long finalBidAmount) {
        long raw = Math.floorDiv(finalBidAmount * RATE_PERCENT, 100L);
        long afterFloor = Math.max(raw, FLOOR_LINDENS);
        return Math.min(afterFloor, finalBidAmount);
    }

    public long payout(long finalBidAmount) {
        return finalBidAmount - commission(finalBidAmount);
    }
}
