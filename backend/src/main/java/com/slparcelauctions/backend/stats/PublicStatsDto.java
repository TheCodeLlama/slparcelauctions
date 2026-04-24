package com.slparcelauctions.backend.stats;

import java.time.OffsetDateTime;

/**
 * Bundled four-count snapshot served by {@code GET /api/v1/stats/public}
 * (Epic 07 sub-spec 1 §5.4). All four numbers travel together in a single
 * response so the homepage can render them with one round-trip.
 *
 * <ul>
 *   <li>{@code activeAuctions} — count of rows with status=ACTIVE.</li>
 *   <li>{@code activeBidTotalL} — SUM of {@code current_bid} across ACTIVE
 *       auctions, in Linden dollars (the trailing {@code L} is the L$
 *       indicator, not a Java type marker). This is "money currently
 *       committed", not lifetime turnover.</li>
 *   <li>{@code completedSales} — count of rows with status=COMPLETED, the
 *       Epic 05 terminal state for a successful escrow handoff.</li>
 *   <li>{@code registeredUsers} — count of rows in the users table.</li>
 *   <li>{@code asOf} — server time the numbers were computed (not the
 *       cache hit time). Diagnostic aid when reports are challenged.</li>
 * </ul>
 */
public record PublicStatsDto(
        long activeAuctions,
        long activeBidTotalL,
        long completedSales,
        long registeredUsers,
        OffsetDateTime asOf) {
}
