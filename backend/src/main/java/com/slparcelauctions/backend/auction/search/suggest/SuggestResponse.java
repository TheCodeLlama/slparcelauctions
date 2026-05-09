package com.slparcelauctions.backend.auction.search.suggest;

import java.util.List;

/**
 * Envelope returned by {@code GET /api/v1/search/suggest}. {@code
 * totalListings} powers the popover's "See all N results" footer; the
 * footer is suppressed when it equals {@code listings.size()} because
 * everything is already on screen.
 */
public record SuggestResponse(
        List<SuggestListingDto> listings,
        List<SuggestRegionDto> regions,
        int totalListings) {

    public static SuggestResponse empty() {
        return new SuggestResponse(List.of(), List.of(), 0);
    }
}
