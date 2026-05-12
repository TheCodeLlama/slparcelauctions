package com.slparcelauctions.backend.realty.moderation.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/listings/reinstate-all}.
 *
 * <p>Drives the cascading bulk-listing-reinstate flow implemented by
 * {@code BulkListingSuspendService.reinstateAll}. {@code notes} is optional
 * audit commentary; when supplied it is capped at 1000 characters and
 * threaded into the admin-action audit row.
 *
 * <p>Sub-project F spec §6.3.
 */
public record BulkReinstateListingsRequest(
    @Size(max = 1000) String notes
) {}
