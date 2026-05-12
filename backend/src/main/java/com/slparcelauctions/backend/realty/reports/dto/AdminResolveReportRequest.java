package com.slparcelauctions.backend.realty.reports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/realty-groups/reports/{publicId}/resolve}.
 *
 * <p>{@code notes} is the admin's resolution write-up; it lands on the report row's
 * {@code resolution_notes} column and is surfaced in the audit trail.
 *
 * <p>{@code escalateTo} is INFORMATIONAL — the backend treats it as a hint and never
 * itself takes the escalation action. The frontend uses it to know whether to
 * immediately open a suspension modal after the resolve call returns (e.g., if the
 * admin selected "Resolve and suspend group", the frontend posts resolve here and
 * then opens the suspend modal pre-filled with the report context). Valid values:
 * {@code null}, {@code "SUSPEND_GROUP"}, {@code "BAN_GROUP"}.
 *
 * <p>Sub-project F spec §6.5, §12.3.
 */
public record AdminResolveReportRequest(
    @NotBlank
    @Size(max = 1000)
    String notes,

    @Pattern(
        regexp = "SUSPEND_GROUP|BAN_GROUP",
        message = "escalateTo must be SUSPEND_GROUP, BAN_GROUP, or null"
    )
    String escalateTo
) {}
