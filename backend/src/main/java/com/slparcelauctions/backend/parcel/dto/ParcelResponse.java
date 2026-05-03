package com.slparcelauctions.backend.parcel.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
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
 *
 * <p>{@code snapshotUrl} is always {@code null} when constructed from an
 * {@link AuctionParcelSnapshot} — the SL parcel image is stored as a
 * photo entry (source {@code SL_PARCEL_SNAPSHOT}) and served via
 * {@code GET /api/v1/photos/{id}}, not as a parcel-level field.
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
     * Constructs a {@code ParcelResponse} from an {@link AuctionParcelSnapshot}.
     * This is the primary factory used by the auction mapper in Phase 3+.
     * {@code snapshotUrl} is set to {@code null} — the SL parcel image is
     * served via the flat {@code GET /api/v1/photos/{id}} endpoint.
     */
    public static ParcelResponse from(AuctionParcelSnapshot s) {
        Region r = s.getRegion();
        Long regionId = r != null ? r.getId() : null;
        Double gridX = r != null ? r.getGridX() : null;
        Double gridY = r != null ? r.getGridY() : null;
        return new ParcelResponse(
                s.getSlParcelUuid(), s.getOwnerUuid(), s.getOwnerType(),
                s.getOwnerName(), s.getParcelName(),
                regionId, s.getRegionName(), s.getRegionMaturityRating(),
                gridX, gridY,
                s.getPositionX(), s.getPositionY(), s.getPositionZ(),
                s.getAreaSqm(), s.getDescription(), null, s.getSlurl(),
                s.getVerifiedAt() != null, s.getVerifiedAt(), s.getLastChecked());
    }

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
