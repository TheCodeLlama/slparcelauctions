package com.slparcelauctions.backend.auction.search;

public enum AuctionSearchSort {
    NEWEST,
    ENDING_SOONEST,
    MOST_BIDS,
    LOWEST_PRICE,
    LARGEST_AREA,
    NEAREST;

    public static AuctionSearchSort fromWire(String value) {
        if (value == null) return NEWEST;
        return switch (value.toLowerCase()) {
            case "newest"         -> NEWEST;
            case "ending_soonest" -> ENDING_SOONEST;
            case "most_bids"      -> MOST_BIDS;
            case "lowest_price"   -> LOWEST_PRICE;
            case "largest_area"   -> LARGEST_AREA;
            case "nearest"        -> NEAREST;
            default -> throw new com.slparcelauctions.backend.auction.search.exception
                    .InvalidFilterValueException("sort", value,
                    "newest, ending_soonest, most_bids, lowest_price, largest_area, nearest");
        };
    }
}
