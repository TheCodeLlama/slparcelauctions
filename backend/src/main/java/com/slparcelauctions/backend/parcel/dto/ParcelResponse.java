package com.slparcelauctions.backend.parcel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.region.Region;

/**
 * Public view of a {@link Parcel} row. Built via {@link #from(Parcel)}.
 * Region-scoped fields ({@code regionName}, {@code gridX}, {@code gridY},
 * {@code maturityRating}) are read through {@code parcel.region.*} but
 * surfaced flat on the response so the frontend doesn't have to traverse a
 * nested region object.
 */
public record ParcelResponse(
        Long id,
        UUID slParcelUuid,
        UUID ownerUuid,
        String ownerType,
        String ownerName,
        String parcelName,
        String regionName,
        Double gridX,
        Double gridY,
        Double positionX,
        Double positionY,
        Double positionZ,
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
        Region r = p.getRegion();
        return new ParcelResponse(
                p.getId(), p.getSlParcelUuid(), p.getOwnerUuid(), p.getOwnerType(),
                p.getOwnerName(), p.getParcelName(),
                r.getName(), r.getGridX(), r.getGridY(),
                p.getPositionX(), p.getPositionY(), p.getPositionZ(),
                p.getAreaSqm(), p.getDescription(), p.getSnapshotUrl(), p.getSlurl(),
                r.getMaturityRating(), p.getVerified(), p.getVerifiedAt(),
                p.getLastChecked(), p.getCreatedAt());
    }
}
