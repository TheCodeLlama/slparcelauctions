package com.slparcelauctions.backend.realty.moderation.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for
 * {@code POST /api/v1/admin/realty-groups/{publicId}/listings/suspend-all}.
 *
 * <p>Drives the cascading bulk-listing-suspend flow implemented by
 * {@code BulkListingSuspendService.suspendAll}. Unlike the group-suspension
 * cascade triggered by {@code AdminRealtyGroupSuspensionController}, this
 * endpoint can run standalone (no linked {@code RealtyGroupSuspension} row)
 * or be tied back to an existing group-suspension row via
 * {@code groupSuspensionPublicId} -- in the latter case the controller
 * resolves the public id through {@code RealtyGroupSuspensionRepository}
 * and passes the entity {@code id} into the service so the bulk
 * {@code listing_suspensions} rows carry the FK back to the originating
 * suspension.
 *
 * <p>{@code notes} is threaded through to the batched admin audit row as
 * {@code details.notes} (omitted when null / blank). It becomes the
 * admin-facing commentary surfaced on the audit-row detail view alongside the
 * {@code count}, {@code groupId}, and {@code bulkActionId} entries.
 *
 * <p>Sub-project F spec §6.3.
 */
public record BulkSuspendListingsRequest(
    @NotBlank @Size(max = 1000) String reason,
    @Size(max = 1000) String notes,
    UUID groupSuspensionPublicId
) {}
