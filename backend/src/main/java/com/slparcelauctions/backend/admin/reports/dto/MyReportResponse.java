package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;
import com.slparcelauctions.backend.admin.reports.ListingReportStatus;

public record MyReportResponse(
    UUID publicId,
    String subject,
    ListingReportReason reason,
    String details,
    ListingReportStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
