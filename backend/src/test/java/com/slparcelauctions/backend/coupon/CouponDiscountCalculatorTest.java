package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CouponDiscountCalculatorTest {

    @Test
    void listingFee_override_returnsValueAsLindens() {
        var result = CouponDiscountCalculator.applyListingFee(
                DiscountOp.OVERRIDE, new BigDecimal("0"), 100L);
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void listingFee_percentOff_appliesAgainstDefault() {
        var result = CouponDiscountCalculator.applyListingFee(
                DiscountOp.PERCENT_OFF, new BigDecimal("50"), 100L);
        assertThat(result).isEqualTo(50L);
    }

    @Test
    void listingFee_flatOff_clampsToZero() {
        var result = CouponDiscountCalculator.applyListingFee(
                DiscountOp.FLAT_OFF, new BigDecimal("150"), 100L);
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void commission_override_returnsValueAsRate() {
        var result = CouponDiscountCalculator.applyCommission(
                DiscountOp.OVERRIDE, new BigDecimal("3.0"), new BigDecimal("0.05"));
        assertThat(result).isEqualByComparingTo("0.03");
    }

    @Test
    void commission_percentOff_appliesAgainstDefault() {
        var result = CouponDiscountCalculator.applyCommission(
                DiscountOp.PERCENT_OFF, new BigDecimal("50"), new BigDecimal("0.05"));
        assertThat(result).isEqualByComparingTo("0.025");
    }

    @Test
    void commission_flatOff_subtractsPoints_clampsToZero() {
        var result = CouponDiscountCalculator.applyCommission(
                DiscountOp.FLAT_OFF, new BigDecimal("10.0"), new BigDecimal("0.05"));
        assertThat(result).isEqualByComparingTo("0.00");
    }
}
