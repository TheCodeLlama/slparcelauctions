package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserFraudFlagRowDto(
    Long flagId,
    Long auctionId,
    UUID auctionPublicId,
    String auctionTitle,
    String reason,
    boolean resolved,
    OffsetDateTime detectedAt
) {}
