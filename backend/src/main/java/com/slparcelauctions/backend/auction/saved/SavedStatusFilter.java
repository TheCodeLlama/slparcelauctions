package com.slparcelauctions.backend.auction.saved;

import com.slparcelauctions.backend.auction.search.exception.InvalidFilterValueException;

/**
 * Status filter for {@code GET /api/v1/me/saved/auctions}. {@code ended_only}
 * is implemented in the service as "NOT IN pre-active + NOT ACTIVE" so any
 * future terminal status added to {@link com.slparcelauctions.backend.auction.AuctionStatus}
 * automatically falls into the ended bucket. See spec §5.4.
 */
public enum SavedStatusFilter {
    ACTIVE_ONLY, ENDED_ONLY, ALL;

    public static SavedStatusFilter fromWire(String v) {
        if (v == null) {
            return ACTIVE_ONLY;
        }
        return switch (v.toLowerCase()) {
            case "active_only" -> ACTIVE_ONLY;
            case "ended_only"  -> ENDED_ONLY;
            case "all"         -> ALL;
            default -> throw new InvalidFilterValueException(
                    "statusFilter", v, "active_only, ended_only, all");
        };
    }
}
