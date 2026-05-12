package com.slparcelauctions.backend.realty.reports.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.dto.AdminSummaryDto;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportReason;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportStatus;

/**
 * Full wire-shape for a single realty-group report, returned by the admin detail
 * endpoint and as the response body of resolve/dismiss operations.
 *
 * <p>Differs from {@link AdminReportRowDto} (the queue row) by also carrying:
 * <ul>
 *   <li>{@code details} — the reporter's free-text body.</li>
 *   <li>{@code resolvedByAdmin} / {@code resolvedAt} / {@code resolutionNotes}
 *       — populated when the report has been triaged (nullable for OPEN rows).</li>
 * </ul>
 *
 * <p>The embedded {@link GroupRef} sub-DTO carries just the realty group's
 * {@code publicId} + {@code name} — enough for the admin UI to build a
 * deep-link to the group's admin page without an extra round-trip.
 *
 * <p>Sub-project F spec §6.5, §12.2.
 */
public record AdminReportDetailDto(
    UUID publicId,
    GroupRef group,
    AdminSummaryDto reporter,
    RealtyGroupReportReason reason,
    String details,
    RealtyGroupReportStatus status,
    AdminSummaryDto resolvedByAdmin,
    OffsetDateTime resolvedAt,
    String resolutionNotes,
    OffsetDateTime createdAt
) {
    /** Compact identifier for the report's target realty group. */
    public record GroupRef(UUID publicId, String name) {}
}
