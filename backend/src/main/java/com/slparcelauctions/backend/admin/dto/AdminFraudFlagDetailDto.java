package com.slparcelauctions.backend.admin.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;

public record AdminFraudFlagDetailDto(
    Long id,
    FraudFlagReason reason,
    OffsetDateTime detectedAt,
    OffsetDateTime resolvedAt,
    String resolvedByDisplayName,
    String adminNotes,
    AuctionContextDto auction,
    Map<String, Object> evidenceJson,
    Map<String, LinkedUserDto> linkedUsers,
    long siblingOpenFlagCount
) {
    public record AuctionContextDto(
        Long id,
        String title,
        AuctionStatus status,
        OffsetDateTime endsAt,
        OffsetDateTime suspendedAt,
        Long sellerUserId,
        String sellerDisplayName
    ) {}

    public record LinkedUserDto(
        Long userId,
        String displayName
    ) {}
}
