package com.slparcelauctions.backend.realty.slgroup.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code DELETE /api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}}.
 *
 * <p>Required for both the non-force path (delegates to
 * {@link com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupService#unregister
 * RealtyGroupSlGroupService.unregister}, which still respects the active-listings gate)
 * and the force path ({@code ?force=true}, which delegates to
 * {@link com.slparcelauctions.backend.realty.slgroup.SlGroupForceUnregisterService#forceUnregister
 * SlGroupForceUnregisterService.forceUnregister} and cascades through
 * {@link com.slparcelauctions.backend.auction.monitoring.BulkListingSuspendService}).
 * The reason is recorded on the row's {@code unregister_reason} column and in the
 * audit-row details.
 *
 * <p>Sub-project F spec §13.5.
 */
public record ForceUnregisterRequest(
        @NotBlank @Size(max = 64) String reason
) {}
