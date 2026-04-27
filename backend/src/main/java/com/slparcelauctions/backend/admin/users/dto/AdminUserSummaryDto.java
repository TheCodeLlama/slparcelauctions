package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.user.Role;

public record AdminUserSummaryDto(
    Long id,
    String email,
    String displayName,
    UUID slAvatarUuid,
    String slDisplayName,
    Role role,
    boolean verified,
    boolean hasActiveBan,
    long completedSales,
    long cancelledWithBids,
    OffsetDateTime createdAt
) {}
