package com.slparcelauctions.backend.auction.search;

import java.math.BigDecimal;

/**
 * Seller card embedded in each {@link AuctionSearchResultDto}. Epic 08
 * sub-spec 1 adds {@code completedSales} so the browse page can render
 * the "N sales" trust signal alongside the average rating without
 * requiring a follow-up call to the profile endpoint.
 *
 * <p>The private denominator counters ({@code cancelledWithBids},
 * {@code escrowExpiredUnfulfilled}) are intentionally omitted — only
 * the server-computed completion rate belongs on the wire, and search
 * cards do not render it today.
 */
public record SellerSummaryDto(
        Long id,
        String displayName,
        String avatarUrl,
        BigDecimal averageRating,
        Integer reviewCount,
        Integer completedSales) {
}
