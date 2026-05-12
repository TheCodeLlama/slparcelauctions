package com.slparcelauctions.backend.realty.reports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/admin/realty-groups/reports/{publicId}/dismiss}.
 *
 * <p>{@code notes} is the admin's justification for closing the report without
 * escalation. The service stamps it on the report row's {@code resolution_notes}
 * column, increments the reporter's {@code dismissedReportsCount} (drives the
 * frivolous-reporter counter), and writes a {@code REALTY_GROUP_REPORT_DISMISS}
 * admin action.
 *
 * <p>Sub-project F spec §6.5, §12.3.
 */
public record AdminDismissReportRequest(
    @NotBlank
    @Size(max = 1000)
    String notes
) {}
