package com.slparcelauctions.backend.realty.reports;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.admin.dto.AdminSummaryDto;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.realty.reports.dto.AdminReportRowDto;
import com.slparcelauctions.backend.user.User;

/**
 * Maps {@link RealtyGroupReport} entities to wire-shape DTOs used by the admin
 * reports queue. Two outputs:
 * <ul>
 *   <li>{@link AdminReportRowDto} — compact row for the queue page.</li>
 *   <li>{@link AdminReportDetailDto} — full detail including the reporter body,
 *       resolution fields, and an embedded {@link AdminReportDetailDto.GroupRef}.</li>
 * </ul>
 *
 * <p>Reads {@code displayName} / {@code publicId} only from the user associations —
 * {@link User#getDisplayName()} already falls back to {@code username} when no
 * display name is set, so the wire never carries a blank name. The realty-group
 * association is read lazily and the mapper is intended to be called from inside
 * a transactional context.
 *
 * <p>Sub-project F spec §6.5.
 */
@Component
public class ReportDtoMapper {

    /** Build the compact row DTO for the admin queue page. */
    public AdminReportRowDto toRow(RealtyGroupReport entity) {
        RealtyGroup group = entity.getRealtyGroup();
        return new AdminReportRowDto(
            entity.getPublicId(),
            group == null ? null : group.getPublicId(),
            group == null ? null : group.getName(),
            toAdminSummary(entity.getReporter()),
            entity.getReason(),
            entity.getStatus(),
            entity.getCreatedAt());
    }

    /** Build the full detail DTO for the admin detail / resolve / dismiss endpoints. */
    public AdminReportDetailDto toDetail(RealtyGroupReport entity) {
        RealtyGroup group = entity.getRealtyGroup();
        AdminReportDetailDto.GroupRef groupRef = group == null
            ? null
            : new AdminReportDetailDto.GroupRef(group.getPublicId(), group.getName());
        return new AdminReportDetailDto(
            entity.getPublicId(),
            groupRef,
            toAdminSummary(entity.getReporter()),
            entity.getReason(),
            entity.getDetails(),
            entity.getStatus(),
            toAdminSummary(entity.getResolvedByAdmin()),
            entity.getResolvedAt(),
            entity.getResolutionNotes(),
            entity.getCreatedAt());
    }

    private static AdminSummaryDto toAdminSummary(User user) {
        if (user == null) return null;
        return new AdminSummaryDto(user.getPublicId(), user.getDisplayName());
    }
}
