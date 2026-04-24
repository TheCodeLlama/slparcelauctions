package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SellerCompletionRateMapperTest {

    @Test
    void bothZero_returnsNull() {
        assertThat(SellerCompletionRateMapper.compute(0, 0)).isNull();
    }

    @Test
    void noCancellations_returns1_00() {
        assertThat(SellerCompletionRateMapper.compute(10, 0))
                .isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void onlyCancellations_returns0_00() {
        assertThat(SellerCompletionRateMapper.compute(0, 5))
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void twoThirds_roundsTo_0_67() {
        assertThat(SellerCompletionRateMapper.compute(2, 1))
                .isEqualByComparingTo(new BigDecimal("0.67"));
    }

    @Test
    void roundsHalfUp() {
        // 5/(5+2) = 0.7142857... -> 0.71
        assertThat(SellerCompletionRateMapper.compute(5, 2))
                .isEqualByComparingTo(new BigDecimal("0.71"));
        // 1/3 = 0.3333... -> 0.33
        assertThat(SellerCompletionRateMapper.compute(1, 2))
                .isEqualByComparingTo(new BigDecimal("0.33"));
    }
}
