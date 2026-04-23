package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EscrowCommissionCalculatorTest {

    private final EscrowCommissionCalculator calc = new EscrowCommissionCalculator();

    @Test
    void computesFivePercentAboveOneThousand() {
        assertThat(calc.commission(1000L)).isEqualTo(50L);
        assertThat(calc.commission(10_000L)).isEqualTo(500L);
        assertThat(calc.commission(100_000L)).isEqualTo(5_000L);
    }

    @Test
    void floorsToFiftyBelowOneThousand() {
        assertThat(calc.commission(500L)).isEqualTo(50L);
        assertThat(calc.commission(100L)).isEqualTo(50L);
    }

    @Test
    void clampsCommissionToFinalBidForPathologicallyLowAmounts() {
        assertThat(calc.commission(1L)).isEqualTo(1L);
        assertThat(calc.commission(25L)).isEqualTo(25L);
        assertThat(calc.commission(49L)).isEqualTo(49L);
        assertThat(calc.commission(50L)).isEqualTo(50L);
    }

    @Test
    void payoutIsAlwaysFinalMinusCommission() {
        assertThat(calc.payout(1L)).isEqualTo(0L);
        assertThat(calc.payout(100L)).isEqualTo(50L);
        assertThat(calc.payout(1000L)).isEqualTo(950L);
        assertThat(calc.payout(10_000L)).isEqualTo(9_500L);
    }
}
