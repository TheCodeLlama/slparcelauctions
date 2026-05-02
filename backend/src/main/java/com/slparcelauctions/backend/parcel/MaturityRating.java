package com.slparcelauctions.backend.parcel;

import java.util.Set;

/**
 * Canonical SLPA maturity rating values used in {@code parcels.maturity_rating},
 * {@code auctions.maturity_rating}, and search filters. SL exposes maturity at
 * the region level using a different vocabulary; once a regions table lands,
 * the SL-side translation will live next to that ingest path.
 */
public final class MaturityRating {

    public static final Set<String> CANONICAL_VALUES = Set.of("GENERAL", "MODERATE", "ADULT");

    private MaturityRating() {
        // constants only
    }
}
