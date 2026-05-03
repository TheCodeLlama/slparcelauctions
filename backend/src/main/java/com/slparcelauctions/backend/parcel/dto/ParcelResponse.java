package com.slparcelauctions.backend.parcel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.region.Region;

/**
 * Public view of a parcel lookup result. Fields are sourced from the SL World
 * API and the resolved {@link com.slparcelauctions.backend.region.Region} row.
 *
 * <p>The {@code id} field has been removed — parcels no longer have their own
 * stable identity in Phase 2; each auction carries its own
 * {@code AuctionParcelSnapshot}. The {@link #from(Parcel)} factory is retained
 * for Phase 4 callers that still compile against the old path; it will be
 * removed when those callers are migrated.
 *
 * <p>Region-scoped fields ({@code regionName}, {@code gridX}, {@code gridY},
 * {@code maturityRating}) are surfaced flat so the frontend doesn't need to
 * traverse a nested region object.
 */
public record ParcelResponse(
        UUID slParcelUuid,
        UUID ownerUuid,
        String ownerType,
        String ownerName,
        String parcelName,
        Long regionId,
        String regionName,
        String regionMaturityRating,
        Double gridX,
        Double gridY,
        Double positionX,
        Double positionY,
        Double positionZ,
        Integer areaSqm,
        String description,
        String snapshotUrl,
        String slurl,
        Boolean verified,
        OffsetDateTime verifiedAt,
        OffsetDateTime lastChecked) {

    /**
     * Constructs a {@code ParcelResponse} from a persisted {@link Parcel} entity.
     * Retained for legacy callers that will be migrated in Phase 4.
     */
    public static ParcelResponse from(Parcel p) {
        Region r = p.getRegion();
        return new ParcelResponse(
                p.getSlParcelUuid(), p.getOwnerUuid(), p.getOwnerType(),
                p.getOwnerName(), p.getParcelName(),
                r.getId(), r.getName(), r.getMaturityRating(),
                r.getGridX(), r.getGridY(),
                p.getPositionX(), p.getPositionY(), p.getPositionZ(),
                p.getAreaSqm(), p.getDescription(), p.getSnapshotUrl(), p.getSlurl(),
                p.getVerified(), p.getVerifiedAt(), p.getLastChecked());
    }
}
