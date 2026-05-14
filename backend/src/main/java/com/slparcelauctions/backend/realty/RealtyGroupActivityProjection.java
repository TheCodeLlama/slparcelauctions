package com.slparcelauctions.backend.realty;

/**
 * Projection over the activity-summary native query on
 * {@link RealtyGroupRepository#findActivity}. Feeds the public profile DTO so
 * the {@code /groups/[slug]} page can render the template's 4-stat grid +
 * Verified-SL-group badge in one round-trip.
 */
public interface RealtyGroupActivityProjection {
    int getActiveListings();
    int getCompletedSales();
    boolean getHasVerifiedSlGroup();
}
