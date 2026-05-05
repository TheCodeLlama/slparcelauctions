package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserCancellationRowDto(
    Long logId,
    Long auctionId,
    UUID auctionPublicId,
    String auctionTitle,
    String cancelledFromStatus,
    boolean hadBids,
    String reason,
    String penaltyKind,
    Long penaltyAmountL,
    Long cancelledByAdminId,
    OffsetDateTime cancelledAt
) {}
