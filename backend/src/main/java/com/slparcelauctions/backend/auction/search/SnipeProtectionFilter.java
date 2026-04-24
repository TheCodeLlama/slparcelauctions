package com.slparcelauctions.backend.auction.search;

public enum SnipeProtectionFilter {
    ANY, TRUE, FALSE;

    public static SnipeProtectionFilter fromWire(String value) {
        if (value == null) return ANY;
        return switch (value.toLowerCase()) {
            case "any"   -> ANY;
            case "true"  -> TRUE;
            case "false" -> FALSE;
            default -> throw new com.slparcelauctions.backend.auction.search.exception
                    .InvalidFilterValueException("snipe_protection", value, "any, true, false");
        };
    }
}
