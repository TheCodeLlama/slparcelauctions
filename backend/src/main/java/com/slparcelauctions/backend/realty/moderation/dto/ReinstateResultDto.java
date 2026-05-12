package com.slparcelauctions.backend.realty.moderation.dto;

/**
 * Response body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/listings/reinstate-all}.
 *
 * <p>{@code reinstatedCount} is the number of bulk-cause {@code listing_suspensions}
 * rows actually lifted by the cascade. A 0 means the group had no active bulk
 * suspensions at the moment the cascade ran (not an error).
 *
 * <p>Sub-project F spec §6.3.
 */
public record ReinstateResultDto(
    int reinstatedCount
) {}
