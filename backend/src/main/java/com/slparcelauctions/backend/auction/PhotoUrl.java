package com.slparcelauctions.backend.auction;

import java.util.Comparator;
import java.util.UUID;

/**
 * Single source of truth for the relative photo-byte URL emitted on every
 * public/admin DTO. The serving endpoint is the flat
 * {@code GET /api/v1/photos/{publicId}} in {@link PhotoController}, whose
 * {@code publicId} path variable is a {@code UUID}.
 *
 * <p>History (mirrors the {@code UserAvatarUrl} fix): two review-side
 * builders ({@code PendingReviewDto.of} and
 * {@code ReviewService.resolvePrimaryPhotoUrl}) hand-rolled
 * {@code "/api/v1/auctions/" + auction.getId() + "/photos/" + photo.getId()
 * + "/bytes"} — a route that <em>does not exist</em> (only
 * POST/DELETE/PATCH live under {@code /api/v1/auctions/{id}/photos}) and
 * fed numeric DB ids besides. Every review thumbnail (dashboard
 * pending-reviews card, auction-detail review list, profile Reviews tab)
 * 404'd in production.
 *
 * <p>This helper makes that mistake impossible: it takes a {@code UUID},
 * so handing it a {@code Long}/{@code long} DB id is a compile error.
 * Every photo-URL builder routes through here with {@code getPublicId()}.
 * {@link #primaryForAuction(Auction)} centralises the previously-triplicated
 * "first photo by sortOrder, else null" projection so there is exactly one
 * producer of an auction's primary-photo URL.
 */
public final class PhotoUrl {

    private PhotoUrl() {}

    /**
     * Relative photo-byte URL for a known, non-null photo {@code publicId}.
     *
     * <p>Plan Task 6 made the bytes endpoint variant-aware via a
     * {@code ?variant=light|dark} query parameter. This helper still emits
     * the bare path (no query string) so legacy single-variant callers
     * continue to work: the bytes endpoint defaults the missing parameter
     * to {@code light}, which is the only variant guaranteed to exist on
     * every row. Theme-pair callers that want to render the dark sibling
     * use {@link #forPhotoLight(UUID)} / {@link #forPhotoDark(UUID)}.
     */
    public static String forPhoto(UUID publicId) {
        return "/api/v1/photos/" + publicId;
    }

    /**
     * Null-tolerant variant: {@code null} when {@code publicId} is
     * {@code null}, otherwise {@link #forPhoto(UUID)}. Used by the
     * primary-photo paths (no photos → {@code null} URL) and by the
     * suggest repository, which projects a nullable {@code public_id}
     * straight off a {@code LEFT JOIN}.
     */
    public static String forPhotoOrNull(UUID publicId) {
        return publicId == null ? null : forPhoto(publicId);
    }

    /**
     * Explicit light-variant URL: {@code /api/v1/photos/{publicId}?variant=light}.
     * Built in the centralized helper so the {@code PhotoUrlGuardTest} flat-path
     * concatenation ban stays intact for callers outside this file (plan Task 6).
     * The light slot is mandatory (the entity's {@code light_object_key} is
     * {@code NOT NULL}), so this URL is always servable for an existing row.
     */
    public static String forPhotoLight(UUID publicId) {
        return "/api/v1/photos/" + publicId + "?variant=light";
    }

    /**
     * Explicit dark-variant URL: {@code /api/v1/photos/{publicId}?variant=dark}.
     * Returns {@code null} when {@code darkObjectKey} on the source entity is
     * null, so callers can pass that nullable column straight through without a
     * branch. The bytes endpoint 404s a dark request on a row that only has the
     * light slot — the URL is therefore only safe to emit when a dark variant
     * actually exists.
     */
    public static String forPhotoDarkOrNull(UUID publicId, String darkObjectKey) {
        return darkObjectKey == null ? null
                : "/api/v1/photos/" + publicId + "?variant=dark";
    }

    /**
     * The auction's primary-photo URL: first {@link AuctionPhoto} by
     * {@code sortOrder}, mapped to {@link #forPhoto(UUID)} on its
     * {@code publicId}; {@code null} when the auction has no photos.
     * Single producer for the dashboard pending-reviews card, the
     * auction-detail review list, the profile Reviews tab, and the
     * cancellation-history list.
     */
    public static String primaryForAuction(Auction auction) {
        return auction.getPhotos().stream()
                .min(Comparator.comparing(AuctionPhoto::getSortOrder))
                .map(p -> forPhoto(p.getPublicId()))
                .orElse(null);
    }
}
