package com.slparcelauctions.backend.auction.search.suggest;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the three native-SQL queries behind the suggest
 * endpoint. The hard caps (5 listings, 3 regions) are spec-fixed
 * (§5.2); only {@code totalListings} is unbounded so the popover footer
 * can advertise the full match count.
 */
@Service
@RequiredArgsConstructor
public class SearchSuggestService {

    private static final int LISTINGS_LIMIT = 5;
    private static final int REGIONS_LIMIT = 3;

    private final SearchSuggestRepository repo;

    public SuggestResponse suggest(String trimmed) {
        return new SuggestResponse(
                repo.findListings(trimmed, LISTINGS_LIMIT),
                repo.findRegions(trimmed, REGIONS_LIMIT),
                repo.countListings(trimmed));
    }
}
