package com.slparcelauctions.backend.parcel;

import java.util.Map;
import java.util.Set;

/**
 * Translates the SL World API's XML {@code <meta name="maturityrating">}
 * values to SLPA canonical storage values.
 *
 * <p>SL's current public product terminology is General / Moderate / Adult
 * (see https://secondlife.com/corporate/maturity), but the World API XML
 * still returns the legacy "PG", "Mature", "Adult" strings. We translate at
 * the ingest boundary so every downstream reader (filters, DTOs, UI) sees
 * the canonical vocabulary only.
 *
 * <p>Exact XML casing is required. If SL ever ships a different casing we
 * want to fail loudly rather than silently normalize — a quiet casing
 * change is a signal something's wrong with the upstream contract.
 */
public final class MaturityRatingNormalizer {

    public static final Set<String> CANONICAL_VALUES = Set.of("GENERAL", "MODERATE", "ADULT");

    private static final Map<String, String> XML_TO_CANONICAL = Map.of(
            "PG", "GENERAL",
            "Mature", "MODERATE",
            "Adult", "ADULT"
    );

    private MaturityRatingNormalizer() {
        // utility class
    }

    public static String normalize(String xmlValue) {
        if (xmlValue == null || xmlValue.isBlank()) {
            throw new IllegalArgumentException(
                    "maturityRating missing from World API response");
        }
        String canonical = XML_TO_CANONICAL.get(xmlValue);
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "Unknown maturityRating from World API: '" + xmlValue
                            + "' (expected one of: " + XML_TO_CANONICAL.keySet() + ")");
        }
        return canonical;
    }
}
