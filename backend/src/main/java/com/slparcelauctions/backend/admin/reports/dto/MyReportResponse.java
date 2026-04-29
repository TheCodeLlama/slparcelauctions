package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;
import com.slparcelauctions.backend.admin.reports.ListingReportStatus;

public record MyReportResponse(
    Long id,
    String subject,
    ListingReportReason reason,
    String details,
    ListingReportStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
