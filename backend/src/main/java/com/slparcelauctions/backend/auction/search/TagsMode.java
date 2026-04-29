package com.slparcelauctions.backend.auction.search;

public enum TagsMode {
    OR, AND;

    public static TagsMode fromWire(String value) {
        if (value == null) return OR;
        return switch (value.toLowerCase()) {
            case "or"  -> OR;
            case "and" -> AND;
            default -> throw new com.slparcelauctions.backend.auction.search.exception
                    .InvalidFilterValueException("tags_mode", value, "or, and");
        };
    }
}
