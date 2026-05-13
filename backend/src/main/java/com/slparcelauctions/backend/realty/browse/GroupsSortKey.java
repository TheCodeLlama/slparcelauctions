package com.slparcelauctions.backend.realty.browse;

/**
 * Sort key for the public {@code GET /api/v1/realty-groups} browse endpoint.
 * Spec section 6.1. Direction is always DESC for every key; tie-break is
 * {@code name ASC} for stable pagination across pages.
 */
public enum GroupsSortKey {
    RATING,
    NEWEST,
    MOST_ACTIVE_LISTINGS,
    MOST_SALES;
}
