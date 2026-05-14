package com.slparcelauctions.backend.realty.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

/**
 * Wire shape for the public group profile endpoint.
 *
 * <p>{@code memberSince} mirrors the group's {@code createdAt}. {@code memberCount} is
 * the count of active member rows
 * ({@link com.slparcelauctions.backend.realty.RealtyGroupMember}). {@code logoUrl} and
 * {@code coverUrl} are relative paths pointing at the byte-serving endpoints (Phase 10);
 * the frontend wraps them with {@code apiUrl(...)} so the browser hits the backend rather
 * than the page origin. Either may be {@code null} when no image has been uploaded.
 *
 * <p>{@code rating} is the aggregated star rating for the group, computed from auction
 * reviews via {@link com.slparcelauctions.backend.realty.rating.GroupRatingService}
 * (sub-project F §16). When no reviews exist the inner DTO's {@code averageRating} is
 * {@code null} (and {@code reviewCount=0}) — the frontend renders that as the
 * "No reviews yet" state rather than a 0.0 placeholder.
 */
public record RealtyGroupPublicDto(
    UUID publicId,
    String name,
    String slug,
    String description,
    String website,
    String logoUrl,
    String coverUrl,
    OffsetDateTime memberSince,
    LeaderCardDto leader,
    List<AgentCardDto> agents,
    int memberSeatLimit,
    int memberCount,
    GroupRatingDto rating,
    /**
     * Count of auctions for this group whose status is {@code SCHEDULED} or
     * {@code LIVE}. Drives the public profile's "Active listings" stat and the
     * {@code Active listings · N} tab label.
     */
    int activeListingsCount,
    /**
     * Count of auctions for this group whose status is {@code COMPLETED}.
     * Drives the public profile's "Lifetime sales" stat.
     */
    int completedSalesCount,
    /**
     * {@code true} when this group has at least one verified SL-group
     * registration. Drives the "Verified SL group" badge on the public profile
     * and the same field on the About tab's details panel.
     */
    boolean hasVerifiedSlGroup
) {}
