package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

public record AdminUserCancellationRowDto(
    Long logId,
    Long auctionId,
    String auctionTitle,
    String cancelledFromStatus,
    boolean hadBids,
    String reason,
    String penaltyKind,
    Long penaltyAmountL,
    Long cancelledByAdminId,
    OffsetDateTime cancelledAt
) {}
