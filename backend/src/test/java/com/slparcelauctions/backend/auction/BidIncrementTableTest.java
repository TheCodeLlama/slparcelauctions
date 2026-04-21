package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tier-boundary coverage for {@link BidIncrementTable}. Every inclusive
 * boundary in DESIGN.md §4.7 is pinned explicitly — both just-below the
 * boundary (e.g. L$999 still in the L$50 tier) and just-at-the-boundary
 * (L$1000 flips to the L$100 tier). Keeps the table stable against a
 * future refactor that slides an edge.
 */
class BidIncrementTableTest {

    @ParameterizedTest
    @CsvSource({
            "0, 50",
            "49, 50",
            "50, 50",
            "999, 50",
            "1000, 100",
            "9999, 100",
            "10000, 500",
            "99999, 500",
            "100000, 1000",
            "500000, 1000"
    })
    void minIncrement_returnsCorrectTier(long currentBid, long expectedIncrement) {
        assertThat(BidIncrementTable.minIncrement(currentBid)).isEqualTo(expectedIncrement);
    }
}
