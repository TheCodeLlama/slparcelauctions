package com.slparcelauctions.backend.auction.search;

public enum ReserveStatusFilter {
    ALL, RESERVE_MET, RESERVE_NOT_MET, NO_RESERVE;

    public static ReserveStatusFilter fromWire(String value) {
        if (value == null) return ALL;
        return switch (value.toLowerCase()) {
            case "all"             -> ALL;
            case "reserve_met"     -> RESERVE_MET;
            case "reserve_not_met" -> RESERVE_NOT_MET;
            case "no_reserve"      -> NO_RESERVE;
            default -> throw new com.slparcelauctions.backend.auction.search.exception
                    .InvalidFilterValueException("reserve_status", value,
                    "all, reserve_met, reserve_not_met, no_reserve");
        };
    }
}
