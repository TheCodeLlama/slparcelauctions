package com.slparcelauctions.backend.auction.search.suggest;

/**
 * One row in the suggest popover's "Regions" group. Click navigates
 * to {@code /browse?region=<name>} on the frontend.
 */
public record SuggestRegionDto(String name, int activeAuctionCount) {
}
