package com.slparcelauctions.backend.realty.moderation.dto;

import java.util.UUID;

/**
 * Response body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/listings/suspend-all}.
 *
 * <p>{@code bulkActionId} is the shared UUID stamped onto every
 * {@code listing_suspensions} row written by the bulk-suspend run — used by
 * the admin UI / audit tooling to correlate the cascade. {@code suspendedCount}
 * is the number of listings actually flipped to {@code SUSPENDED}; a 0 means
 * the group had no active listings at the moment the cascade ran (not an error).
 *
 * <p>Sub-project F spec §6.3.
 */
public record BulkSuspendResultDto(
    UUID bulkActionId,
    int suspendedCount
) {}
