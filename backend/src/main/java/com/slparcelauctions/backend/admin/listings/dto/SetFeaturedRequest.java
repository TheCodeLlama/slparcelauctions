package com.slparcelauctions.backend.admin.listings.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.FutureOrPresent;

/**
 * PATCH body for {@code /api/v1/admin/listings/{publicId}/featured}.
 *
 * <p>{@code featured = true} flips the auction's {@code is_featured} flag
 * on; {@code featuredUntil = null} means "permanent until admin toggles
 * off." {@code featured = false} clears the flag and ignores
 * {@code featuredUntil} (mismatched combination is rejected by the
 * service with {@code FEATURED_UNTIL_REQUIRES_FEATURED_TRUE}).
 */
public record SetFeaturedRequest(
    boolean featured,
    @FutureOrPresent OffsetDateTime featuredUntil
) {}
