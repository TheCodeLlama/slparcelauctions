package com.slparcelauctions.backend.admin.reports.dto;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportRequest(
    @NotBlank @Size(max = 100) String subject,
    @NotNull ListingReportReason reason,
    @NotBlank @Size(max = 2000) String details
) {}
