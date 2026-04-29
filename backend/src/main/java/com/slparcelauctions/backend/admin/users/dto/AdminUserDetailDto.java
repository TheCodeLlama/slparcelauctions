package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.ban.BanType;
import com.slparcelauctions.backend.user.Role;

public record AdminUserDetailDto(
    Long id,
    String email,
    String displayName,
    UUID slAvatarUuid,
    String slDisplayName,
    Role role,
    boolean verified,
    OffsetDateTime verifiedAt,
    OffsetDateTime createdAt,
    long completedSales,
    long cancelledWithBids,
    long escrowExpiredUnfulfilled,
    long dismissedReportsCount,
    long penaltyBalanceOwed,
    OffsetDateTime listingSuspensionUntil,
    boolean bannedFromListing,
    ActiveBanSummary activeBan
) {
    public record ActiveBanSummary(
        Long id,
        BanType banType,
        String reasonText,
        OffsetDateTime expiresAt
    ) {}
}
