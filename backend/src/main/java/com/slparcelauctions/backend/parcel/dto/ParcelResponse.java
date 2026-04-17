package com.slparcelauctions.backend.parcel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.parcel.Parcel;

/**
 * Public view of a {@link Parcel} row. Built via {@link #from(Parcel)}.
 * Shared across auctions — this DTO deliberately exposes only the
 * metadata fields populated by World API + Map API (no layout map).
 */
public record ParcelResponse(
        Long id,
        UUID slParcelUuid,
        UUID ownerUuid,
        String ownerType,
        String regionName,
        Double gridX,
        Double gridY,
        String continentName,
        Integer areaSqm,
        String description,
        String snapshotUrl,
        String slurl,
        String maturityRating,
        Boolean verified,
        OffsetDateTime verifiedAt,
        OffsetDateTime lastChecked,
        OffsetDateTime createdAt) {

    public static ParcelResponse from(Parcel p) {
        return new ParcelResponse(
                p.getId(), p.getSlParcelUuid(), p.getOwnerUuid(), p.getOwnerType(),
                p.getRegionName(), p.getGridX(), p.getGridY(), p.getContinentName(),
                p.getAreaSqm(), p.getDescription(), p.getSnapshotUrl(), p.getSlurl(),
                p.getMaturityRating(), p.getVerified(), p.getVerifiedAt(),
                p.getLastChecked(), p.getCreatedAt());
    }
}
