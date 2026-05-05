package com.slparcelauctions.backend.parcel;

import java.util.Map;
import java.util.Set;

import com.slparcelauctions.backend.sl.ParcelIngestException;

/**
 * Canonical SLParcels maturity rating values used in {@code regions.maturity_rating}
 * and search filters. Plus the SL-side translator: SL exposes maturity on the
 * region page as a {@code mat} meta tag using a different vocabulary
 * ({@code PG_NOT}/{@code M_NOT}/{@code AO_NOT}); {@link #fromSlCode} maps to
 * canonical values at ingest.
 */
public final class MaturityRating {

    public static final Set<String> CANONICAL_VALUES = Set.of("GENERAL", "MODERATE", "ADULT");

    private static final Map<String, String> SL_TO_CANONICAL = Map.of(
            "PG_NOT", "GENERAL",
            "M_NOT", "MODERATE",
            "AO_NOT", "ADULT"
    );

    private MaturityRating() {
        // constants only
    }

    /**
     * Translate the {@code mat} meta value from
     * {@code world.secondlife.com/region/{uuid}} to a canonical SLParcels value.
     * Throws {@link ParcelIngestException} (mapped to 422 by
     * {@code GlobalExceptionHandler}) on null/blank/unknown input — a missing
     * or unrecognized {@code mat} meta means SL's region page contract has
     * shifted, and we want a clean error rather than persisting an invalid row.
     */
    public static String fromSlCode(String slCode) {
        if (slCode == null || slCode.isBlank()) {
            throw new ParcelIngestException(
                    "mat missing from region page");
        }
        String canonical = SL_TO_CANONICAL.get(slCode);
        if (canonical == null) {
            throw new ParcelIngestException(
                    "Unknown mat code from region page: '" + slCode
                            + "' (expected one of: " + SL_TO_CANONICAL.keySet() + ")");
        }
        return canonical;
    }
}
