package com.slparcelauctions.backend.auction.featured;

/**
 * Three featured rows surfaced on the homepage. Each value owns its own
 * Redis key so the per-endpoint caches do not collide and an individual
 * row can be invalidated independently.
 *
 * <p>Defined in Epic 07 sub-spec 1 §5.2 (featured endpoints).
 */
public enum FeaturedCategory {
    ENDING_SOON("slpa:featured:ending-soon"),
    JUST_LISTED("slpa:featured:just-listed"),
    MOST_ACTIVE("slpa:featured:most-active");

    private final String cacheKey;

    FeaturedCategory(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String cacheKey() {
        return cacheKey;
    }
}
