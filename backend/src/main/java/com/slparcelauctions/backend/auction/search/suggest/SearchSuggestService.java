package com.slparcelauctions.backend.auction.search.suggest;

import java.util.List;

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
    /**
     * The Browse {@code near_region} autocomplete listbox shows more
     * rows than the header-overlay's 3-region group, and it draws from
     * the full {@code regions} table rather than active-auction
     * regions, so it gets its own (larger) cap.
     */
    private static final int RESOLVABLE_REGIONS_LIMIT = 10;

    private final SearchSuggestRepository repo;

    public SuggestResponse suggest(String trimmed) {
        return new SuggestResponse(
                repo.findListings(trimmed, LISTINGS_LIMIT),
                repo.findRegions(trimmed, REGIONS_LIMIT),
                repo.countListings(trimmed));
    }

    /**
     * Region-only variant powering the Browse {@code near_region}
     * autocomplete. Skips the listing queries entirely and sources
     * suggestions from the full {@code regions} table so every
     * suggestion is a resolvable distance anchor (selecting one never
     * 400s the search). Returns the standard {@link SuggestResponse}
     * envelope with empty listings / zero count for shape parity.
     */
    public SuggestResponse suggestRegionsOnly(String trimmed) {
        return new SuggestResponse(
                List.of(),
                repo.findResolvableRegions(trimmed, RESOLVABLE_REGIONS_LIMIT),
                0);
    }
}
