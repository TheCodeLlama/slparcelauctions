package com.slparcelauctions.backend.realty.reports.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.dto.AdminSummaryDto;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportReason;
import com.slparcelauctions.backend.realty.reports.RealtyGroupReportStatus;

/**
 * Compact wire-shape for a single row in the admin realty-group reports queue.
 * Returned as the page contents of {@code GET /api/v1/admin/realty-groups/reports}.
 *
 * <p>Carries enough surface to render a row + sort/filter chip:
 * <ul>
 *   <li>{@code publicId} — the report's public id (used as the row link target).</li>
 *   <li>{@code groupPublicId} / {@code groupName} — the target realty group.</li>
 *   <li>{@code reporter} — {@link AdminSummaryDto} identifying the user who filed it.</li>
 *   <li>{@code reason} / {@code status} / {@code createdAt} — chip + sort columns.</li>
 * </ul>
 *
 * <p>The detail view ({@link AdminReportDetailDto}) carries the full report —
 * including the free-text {@code details} body and resolution fields — and is
 * fetched on demand when an admin opens a queue row.
 *
 * <p>Sub-project F spec §6.5, §12.2.
 */
public record AdminReportRowDto(
    UUID publicId,
    UUID groupPublicId,
    String groupName,
    AdminSummaryDto reporter,
    RealtyGroupReportReason reason,
    RealtyGroupReportStatus status,
    OffsetDateTime createdAt
) {}
