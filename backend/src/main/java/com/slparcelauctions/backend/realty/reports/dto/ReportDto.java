package com.slparcelauctions.backend.realty.reports.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.realty.reports.RealtyGroupReport;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportReason;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportStatus;

/**
 * Wire representation of a {@link RealtyGroupReport} on the reporter-facing surface.
 * Returned by {@code POST /api/v1/realty-groups/{publicId}/reports} (201). The shape
 * is intentionally narrow — the reporter sees confirmation that their report exists
 * and what status it is in, but not the admin-only triage fields (resolver, resolution
 * notes, dismissed-counter bumps).
 *
 * <p>The {@code groupPublicId} is denormalised here so the frontend can render an
 * affirmative "report filed against <group>" toast without holding the original request
 * payload (the path-variable {@code publicId}) in scope.
 *
 * <p>Sub-project F spec §6.1, §12.1.
 */
public record ReportDto(
    UUID publicId,
    UUID groupPublicId,
    RealtyGroupReportReason reason,
    RealtyGroupReportStatus status,
    OffsetDateTime createdAt
) {

    /**
     * Map a persisted {@link RealtyGroupReport} row to its wire representation. Reads
     * the lazy {@code realtyGroup} association solely for its {@code publicId} — the
     * service-layer call site already has the row attached to the active transaction,
     * so the lazy access is cheap.
     */
    public static ReportDto from(RealtyGroupReport row) {
        return new ReportDto(
            row.getPublicId(),
            row.getRealtyGroup() == null ? null : row.getRealtyGroup().getPublicId(),
            row.getReason(),
            row.getStatus(),
            row.getCreatedAt()
        );
    }
}
