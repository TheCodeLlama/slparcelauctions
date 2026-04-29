package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;
import com.slparcelauctions.backend.admin.reports.ListingReportStatus;

public record AdminReportDetailDto(
    Long id, ListingReportReason reason, String subject, String details,
    ListingReportStatus status, String adminNotes,
    OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime reviewedAt,
    Long reporterUserId, String reporterDisplayName, long reporterDismissedReportsCount,
    String reviewedByDisplayName
) {}
