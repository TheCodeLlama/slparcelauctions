package com.slparcelauctions.backend.admin.listings.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.AuctionStatus;

/**
 * One row in the admin listings table. {@code currentBid} and {@code bidCount}
 * mirror the entity defaults (0 / 0 when there are no bids); {@code saveCount}
 * is 0 when the LEFT JOIN to {@code saved_auctions} matches nothing.
 *
 * <p>Verification lifecycle is captured by {@code status} (DRAFT / DRAFT_PAID /
 * VERIFICATION_PENDING / VERIFICATION_FAILED / ACTIVE / ...) — there is no
 * separate verification field on the auction.
 */
public record AdminListingRowDto(
    UUID publicId,
    String title,
    UUID sellerPublicId,
    String sellerUsername,
    AuctionStatus status,
    boolean hasReserve,
    OffsetDateTime createdAt,
    Long startingBid,
    Long currentBid,
    Integer bidCount,
    Long saveCount,
    OffsetDateTime endsAt,
    String region
) {}
