package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SellerCompletionRateMapperTest {

    @Test
    void allCountersZero_returnsNull() {
        assertThat(SellerCompletionRateMapper.compute(0, 0, 0)).isNull();
    }

    @Test
    void noCancellationsOrExpiredUnfulfilled_returns1_00() {
        assertThat(SellerCompletionRateMapper.compute(10, 0, 0))
                .isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void onlyCancellations_returns0_00() {
        assertThat(SellerCompletionRateMapper.compute(0, 5, 0))
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void onlyExpiredUnfulfilled_returns0_00() {
        assertThat(SellerCompletionRateMapper.compute(0, 0, 3))
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void expiredUnfulfilledDragsTheRate() {
        // 5 completed / (5 + 0 + 5) = 0.50 — an unfulfilled transfer
        // counts against the denominator the same way a cancellation does.
        assertThat(SellerCompletionRateMapper.compute(5, 0, 5))
                .isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    void threeWayDenominator_roundsHalfUp() {
        // 10 / (10 + 2 + 3) = 10/15 = 0.6666... -> 0.67
        assertThat(SellerCompletionRateMapper.compute(10, 2, 3))
                .isEqualByComparingTo(new BigDecimal("0.67"));
    }

    @Test
    void twoThirds_roundsTo_0_67() {
        assertThat(SellerCompletionRateMapper.compute(2, 1, 0))
                .isEqualByComparingTo(new BigDecimal("0.67"));
    }

    @Test
    void roundsHalfUp_moreCases() {
        // 5/(5+2+0) = 0.7142857... -> 0.71
        assertThat(SellerCompletionRateMapper.compute(5, 2, 0))
                .isEqualByComparingTo(new BigDecimal("0.71"));
        // 1/(1+2+0) = 0.3333... -> 0.33
        assertThat(SellerCompletionRateMapper.compute(1, 2, 0))
                .isEqualByComparingTo(new BigDecimal("0.33"));
    }
}
