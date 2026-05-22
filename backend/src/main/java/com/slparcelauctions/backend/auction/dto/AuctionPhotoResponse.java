package com.slparcelauctions.backend.auction.dto;

import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.auction.PhotoSource;
import com.slparcelauctions.backend.auction.PhotoUrl;

/**
 * Wire shape for a per-auction photo row. Plan Task 6 split the legacy
 * single {@code url} field into explicit {@code lightUrl} + {@code darkUrl}
 * URLs to carry the theme-pair on every photo. Both URLs route through
 * {@link PhotoUrl} (the single Java producer for {@code /api/v1/photos/...}
 * URLs); the bytes endpoint accepts {@code ?variant=light|dark} and 404s a
 * dark request on rows without a dark sibling.
 *
 * <p>{@code lightUrl} is always non-null because the entity's
 * {@code light_object_key} column is {@code NOT NULL}; {@code darkUrl} is
 * {@code null} when the row has no dark variant uploaded (legacy rows and
 * any photo whose owner has not added a dark slot yet). The frontend's
 * {@code ThemedImage} helper falls back to the populated sibling when the
 * caller's preferred variant is null.
 *
 * <p>{@code source} is surfaced so the photo-manager UI can gate the
 * dual-slot affordance to {@code USER_DEFAULT_COVER} / {@code GROUP_DEFAULT_COVER}
 * rows (plan Task 12); seller uploads and SL parcel snapshots stay
 * single-slot.
 */
public record AuctionPhotoResponse(
        UUID publicId,
        String lightUrl,
        String darkUrl,
        PhotoSource source,
        int sortOrder) {

    public static AuctionPhotoResponse from(AuctionPhoto p) {
        return new AuctionPhotoResponse(
                p.getPublicId(),
                PhotoUrl.forPhotoLight(p.getPublicId()),
                PhotoUrl.forPhotoDarkOrNull(p.getPublicId(), p.getDarkObjectKey()),
                p.getSource(),
                p.getSortOrder());
    }
}
