package com.slparcelauctions.backend.realty.browse.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

/**
 * Wire shape for one card in the public groups directory. Spec section 6.1.
 *
 * <p>{@code tagline} is the description server-truncated to 120 chars + ellipsis.
 *
 * <p>Logo + cover ship as dual light/dark URLs (plan Task 2). Each
 * {@code *Url} field is a relative path pointing at the byte-serving GET with
 * {@code ?variant=light|dark}; the frontend wraps them with {@code apiUrl()}
 * and picks the variant matching the active theme. Any field may be {@code null}
 * when the corresponding (surface, variant) slot has never been uploaded.
 *
 * <p>{@code hasVerifiedSlGroup} is deliberately absent — the listing-level
 * filter excludes unverified groups, so the field would always be true on the
 * wire.
 */
public record RealtyGroupCardDto(
        UUID publicId,
        String name,
        String slug,
        String tagline,
        String logoLightUrl,
        String logoDarkUrl,
        String coverLightUrl,
        String coverDarkUrl,
        OffsetDateTime foundedAt,
        int memberCount,
        int memberSeatLimit,
        int activeListingsCount,
        int completedSalesCount,
        GroupRatingDto rating) {
}
