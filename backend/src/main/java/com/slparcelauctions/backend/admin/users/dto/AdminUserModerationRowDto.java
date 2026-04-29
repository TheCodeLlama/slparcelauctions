package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

public record AdminUserModerationRowDto(
    Long actionId,
    String actionType,
    String adminDisplayName,
    String notes,
    OffsetDateTime createdAt
) {}
