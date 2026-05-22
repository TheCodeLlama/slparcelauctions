package com.slparcelauctions.backend.auction.search.suggest;

import java.util.List;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.AuctionConfigProperties;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the three native-SQL queries behind the suggest
 * endpoint. The row caps are externalized to {@code slpa.auction.*}
 * ({@code search-suggest-listings-limit} / {@code -regions-limit} /
 * {@code -resolvable-regions-limit}); only {@code totalListings} is
 * unbounded so the popover footer can advertise the full match count.
 */
@Service
@RequiredArgsConstructor
public class SearchSuggestService {

    private final SearchSuggestRepository repo;
    private final AuctionConfigProperties config;

    public SuggestResponse suggest(String trimmed) {
        return new SuggestResponse(
                repo.findListings(trimmed, config.searchSuggestListingsLimit()),
                repo.findRegions(trimmed, config.searchSuggestRegionsLimit()),
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
                repo.findResolvableRegions(trimmed, config.searchSuggestResolvableRegionsLimit()),
                0);
    }
}
