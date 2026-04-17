package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

/**
 * Typed result of parsing the World API HTML page for a parcel. All fields
 * are best-effort - World API may omit meta tags for some parcels. Callers
 * must handle null.
 */
public record ParcelMetadata(
        UUID parcelUuid,
        UUID ownerUuid,
        String ownerType,       // "agent" or "group"
        String parcelName,
        String regionName,
        Integer areaSqm,
        String description,
        String snapshotUrl,
        String maturityRating,   // "PG", "MATURE", "ADULT"
        Double positionX,
        Double positionY,
        Double positionZ) {
}
