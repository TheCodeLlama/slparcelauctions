package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class EscrowCommissionCalculatorTest {

    private static final BigDecimal RATE_5PCT = new BigDecimal("0.0500");
    private static final BigDecimal RATE_3PCT = new BigDecimal("0.0300");

    private final EscrowCommissionCalculator calc = new EscrowCommissionCalculator(50L);

    @Test
    void computesFivePercentAboveOneThousand() {
        assertThat(calc.commission(1000L, RATE_5PCT)).isEqualTo(50L);
        assertThat(calc.commission(10_000L, RATE_5PCT)).isEqualTo(500L);
        assertThat(calc.commission(100_000L, RATE_5PCT)).isEqualTo(5_000L);
    }

    @Test
    void floorsToFiftyBelowOneThousand() {
        assertThat(calc.commission(500L, RATE_5PCT)).isEqualTo(50L);
        assertThat(calc.commission(100L, RATE_5PCT)).isEqualTo(50L);
    }

    @Test
    void clampsCommissionToFinalBidForPathologicallyLowAmounts() {
        assertThat(calc.commission(1L, RATE_5PCT)).isEqualTo(1L);
        assertThat(calc.commission(25L, RATE_5PCT)).isEqualTo(25L);
        assertThat(calc.commission(49L, RATE_5PCT)).isEqualTo(49L);
        assertThat(calc.commission(50L, RATE_5PCT)).isEqualTo(50L);
    }

    @Test
    void payoutIsAlwaysFinalMinusCommission() {
        assertThat(calc.payout(1L, RATE_5PCT)).isEqualTo(0L);
        assertThat(calc.payout(100L, RATE_5PCT)).isEqualTo(50L);
        assertThat(calc.payout(1000L, RATE_5PCT)).isEqualTo(950L);
        assertThat(calc.payout(10_000L, RATE_5PCT)).isEqualTo(9_500L);
    }

    @Test
    void honorsPerAuctionRateBelowTheDefault() {
        // A coupon-discounted auction carries a lower commissionRate snapshot.
        // The calculator must charge that rate, not a hardcoded 5%. At
        // L$100,000 a 3% rate yields L$3,000 commission (not L$5,000).
        assertThat(calc.commission(100_000L, RATE_3PCT)).isEqualTo(3_000L);
        assertThat(calc.payout(100_000L, RATE_3PCT)).isEqualTo(97_000L);
        // And a different rate at L$10,000: 3% -> L$300.
        assertThat(calc.commission(10_000L, RATE_3PCT)).isEqualTo(300L);
    }

    @Test
    void floorStillAppliesUnderADiscountedRate() {
        // The L$50 floor is global; a discounted rate does not waive it.
        // 3% of L$1,000 is L$30, which floors up to L$50.
        assertThat(calc.commission(1000L, RATE_3PCT)).isEqualTo(50L);
    }

    @Test
    void floorIsConfigDriven() {
        EscrowCommissionCalculator lowFloor = new EscrowCommissionCalculator(10L);
        assertThat(lowFloor.commission(100L, RATE_5PCT)).isEqualTo(10L);
        assertThat(lowFloor.commission(1000L, RATE_5PCT)).isEqualTo(50L);
    }
}
