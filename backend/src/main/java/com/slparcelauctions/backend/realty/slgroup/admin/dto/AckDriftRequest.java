package com.slparcelauctions.backend.realty.slgroup.admin.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/ack-drift}.
 *
 * <p>The admin provides optional free-form notes describing why the drift was
 * acknowledged (e.g. "Confirmed manager handover with leader via SL IM").
 * Carried through to the audit row but not stored on the SL group row itself —
 * the row only retains the acknowledger and timestamp.
 *
 * <p>Sub-project F spec §13.4.
 */
public record AckDriftRequest(
        @Size(max = 2000) String notes
) {}
