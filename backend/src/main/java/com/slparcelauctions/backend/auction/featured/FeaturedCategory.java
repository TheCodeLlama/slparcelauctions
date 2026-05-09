package com.slparcelauctions.backend.auction.featured;

/**
 * Three featured rows surfaced on the homepage. Each value owns its own
 * Redis key so the per-endpoint caches do not collide and an individual
 * row can be invalidated independently.
 *
 * <p>The {@code FEATURED} rail is admin-curated (see
 * {@code is_featured}/{@code featured_until} on {@code auctions}) and
 * auto-fills empty slots from the highest-{@code current_bid} ACTIVE
 * auctions. {@code TRENDING} is computed as a 24h weighted score:
 * {@code bids * 2 + saves}.
 */
public enum FeaturedCategory {
    FEATURED("slpa:featured:rail:featured"),
    ENDING_SOON("slpa:featured:rail:ending-soon"),
    TRENDING("slpa:featured:rail:trending");

    private final String cacheKey;

    FeaturedCategory(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String cacheKey() {
        return cacheKey;
    }
}
