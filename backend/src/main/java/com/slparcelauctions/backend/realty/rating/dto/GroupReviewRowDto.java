package com.slparcelauctions.backend.realty.rating.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Single row of the dedicated group-reviews page (sub-project G §13). All
 * fields are public-safe — {@code publicId}s only, never internal Long PKs.
 * The {@code auctionTitle} carries the parcel name displayed in the auction
 * header at the time the review was left.
 *
 * <p>{@code comment} is the reviewer's free-text body (mapped from the
 * {@code reviews.text} column). It is nullable: rating-only reviews are
 * legitimate input, and visibility is enforced by the underlying query
 * (only visible reviews are listed).
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-final-cleanup-design.md} §13.
 */
public record GroupReviewRowDto(
        UUID reviewerPublicId,
        String reviewerDisplayName,
        int rating,
        String comment,
        UUID auctionPublicId,
        String auctionTitle,
        Instant createdAt) {
}
