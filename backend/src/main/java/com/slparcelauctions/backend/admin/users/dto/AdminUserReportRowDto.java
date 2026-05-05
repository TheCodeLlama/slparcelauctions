package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserReportRowDto(
    Long reportId,
    Long auctionId,
    UUID auctionPublicId,
    String auctionTitle,
    String reason,
    String status,
    String direction,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
