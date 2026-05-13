package com.slparcelauctions.backend.realty.browse.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

/**
 * Wire shape for one card in the public groups directory. Spec section 6.1.
 *
 * <p>{@code tagline} is the description server-truncated to 120 chars + ellipsis.
 * {@code logoUrl} and {@code coverUrl} are relative paths; the frontend wraps
 * them with {@code apiUrl()}. {@code hasVerifiedSlGroup} is deliberately
 * absent — the listing-level filter excludes unverified groups, so the
 * field would always be true on the wire.
 */
public record RealtyGroupCardDto(
        UUID publicId,
        String name,
        String slug,
        String tagline,
        String logoUrl,
        String coverUrl,
        OffsetDateTime foundedAt,
        int memberCount,
        int memberSeatLimit,
        int activeListingsCount,
        int completedSalesCount,
        GroupRatingDto rating) {
}
