package com.slparcelauctions.backend.admin.reports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminReportActionRequest(@NotBlank @Size(max = 1000) String notes) {}
