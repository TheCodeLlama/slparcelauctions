package com.slparcelauctions.backend.promotion;

import java.time.Duration;

/**
 * Test fixture producing a {@link PromotionConfigProperties} instance with the
 * production-default values from {@code application.yml} ({@code slpa.promotions.*}).
 * Used by unit tests that construct services directly rather than through the
 * Spring context.
 */
public final class PromotionConfigPropertiesFixture {

    private PromotionConfigPropertiesFixture() {
    }

    /** Production defaults, matching {@code slpa.promotions.*} in application.yml. */
    public static PromotionConfigProperties defaults() {
        return new PromotionConfigProperties(
                500L,                      // featuredPriceLindens
                5,                         // featuredSlotCount
                Duration.ofSeconds(30));   // featuredBoardCycle
    }
}
