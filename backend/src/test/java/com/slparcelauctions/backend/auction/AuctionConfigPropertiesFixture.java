package com.slparcelauctions.backend.auction;

import java.time.Duration;

/**
 * Test fixture producing an {@link AuctionConfigProperties} instance with the
 * production-default values from {@code application.yml} ({@code slpa.auction.*}).
 * Used by unit tests that construct services directly rather than through the
 * Spring context.
 */
public final class AuctionConfigPropertiesFixture {

    private AuctionConfigPropertiesFixture() {
    }

    /** Production defaults, matching {@code slpa.auction.*} in application.yml. */
    public static AuctionConfigProperties defaults() {
        return new AuctionConfigProperties(
                500,                       // savedAuctionsCap
                100,                       // searchMaxPageSize
                50,                        // searchMaxDistance
                10,                        // searchDefaultDistance
                24,                        // searchDefaultPageSize
                50,                        // cancellationStatusMaxPage
                20,                        // myBidsDefaultPageSize
                5,                         // searchSuggestListingsLimit
                3,                         // searchSuggestRegionsLimit
                10,                        // searchSuggestResolvableRegionsLimit
                Duration.ofSeconds(5),     // snapshotFetchTimeout
                Duration.ofSeconds(60),    // featuredCacheTtl
                Duration.ofSeconds(30),    // searchCacheTtl
                500L,                      // featuredPriceLindens
                5,                         // featuredSlotCount
                30);                       // featuredBoardCycleSeconds
    }
}
