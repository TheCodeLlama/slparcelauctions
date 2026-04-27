package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

public record AdminUserReportRowDto(
    Long reportId,
    Long auctionId,
    String auctionTitle,
    String reason,
    String status,
    String direction,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
