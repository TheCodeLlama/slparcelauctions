package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

/**
 * Typed result of parsing the World API HTML page for a parcel. All fields
 * are best-effort - World API may omit meta tags for some parcels. Callers
 * must handle null.
 *
 * <p>{@code maturityRating} is no longer populated from the parcel page —
 * SL scopes maturity to the region. The field is retained on the DTO purely
 * to avoid churning every test site that constructs {@code ParcelMetadata}
 * positionally; the value is always null at ingest, and the canonical
 * maturity is read off {@code parcel.region.maturityRating}.
 */
public record ParcelMetadata(
        UUID parcelUuid,
        UUID ownerUuid,
        String ownerType,       // "agent" or "group"
        String ownerName,       // SL display name; null/blank when ownertype=group
        String parcelName,
        String regionName,
        Integer areaSqm,
        String description,
        String snapshotUrl,
        String maturityRating,   // always null at ingest — see class doc
        Double positionX,
        Double positionY,
        Double positionZ) {
}
