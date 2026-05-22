package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tier-boundary coverage for {@link BidIncrementSuggester}. Pins the
 * suggestion returned at each tier boundary so a refactor cannot silently
 * slide a threshold.
 */
class BidIncrementSuggesterTest {

    @Test void below1k_suggests50()   { assertThat(BidIncrementSuggester.suggestedIncrement(999L)).isEqualTo(50L); }
    @Test void at1k_suggests100()     { assertThat(BidIncrementSuggester.suggestedIncrement(1_000L)).isEqualTo(100L); }
    @Test void at10k_suggests500()    { assertThat(BidIncrementSuggester.suggestedIncrement(10_000L)).isEqualTo(500L); }
    @Test void at100k_suggests1000()  { assertThat(BidIncrementSuggester.suggestedIncrement(100_000L)).isEqualTo(1_000L); }
    @Test void zero_suggests50()      { assertThat(BidIncrementSuggester.suggestedIncrement(0L)).isEqualTo(50L); }
}
