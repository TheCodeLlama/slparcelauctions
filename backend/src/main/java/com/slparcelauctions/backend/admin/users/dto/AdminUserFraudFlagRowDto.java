package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

public record AdminUserFraudFlagRowDto(
    Long flagId,
    Long auctionId,
    String auctionTitle,
    String reason,
    boolean resolved,
    OffsetDateTime detectedAt
) {}
