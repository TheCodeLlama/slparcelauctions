package com.slparcelauctions.backend.admin.ban.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.ban.BanReasonCategory;
import com.slparcelauctions.backend.admin.ban.BanType;

public record AdminBanRowDto(
    Long id,
    BanType banType,
    String ipAddress,
    UUID slAvatarUuid,
    Long avatarLinkedUserId,
    String avatarLinkedDisplayName,
    String firstSeenIp,
    BanReasonCategory reasonCategory,
    String reasonText,
    Long bannedByUserId,
    String bannedByDisplayName,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime liftedAt,
    Long liftedByUserId,
    String liftedByDisplayName,
    String liftedReason
) {}
