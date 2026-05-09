package com.slparcelauctions.backend.admin.listings.dto;

import java.util.List;

import com.slparcelauctions.backend.auction.AuctionStatus;

/**
 * Parsed filter state for the admin listings list endpoint. Empty
 * {@code statuses} means "all statuses" (the controller honors what the
 * frontend sends; the frontend supplies its own no-filter default).
 *
 * <p>{@code search} is already lowercased and wrapped with {@code %} sentinels
 * for the JPQL/native LIKE bind.
 *
 * <p>{@code featured} = {@code TRUE} narrows to currently-featured rows
 * (is_featured = true AND featured_until is in the future or null).
 * {@code FALSE} or {@code null} are both no-ops — the filter doesn't apply.
 */
public record AdminListingFilterParams(
    String search,
    List<AuctionStatus> statuses,
    Boolean hasReserve,
    Boolean featured
) {}
