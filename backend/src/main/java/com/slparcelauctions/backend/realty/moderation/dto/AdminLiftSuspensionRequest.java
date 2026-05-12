package com.slparcelauctions.backend.realty.moderation.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for
 * {@code DELETE /api/v1/admin/realty-groups/{publicId}/suspensions/{suspensionPublicId}}.
 *
 * <p>{@code notes} is optional (admins may lift a suspension without commentary);
 * when supplied it is capped at 1000 characters. {@code bulkReinstateListings} opts
 * into the cascading bulk listing reinstate flow (implemented by
 * {@code BulkListingSuspendService} in Task 11).
 *
 * <p>Sub-project F spec §6.2, §9.
 */
public record AdminLiftSuspensionRequest(
    @Size(max = 1000) String notes,
    boolean bulkReinstateListings
) {}
