package com.slparcelauctions.backend.admin.infrastructure.bots;

import java.time.OffsetDateTime;

public record BotPoolHealthRow(
        Long workerId,
        String name,
        String slUuid,
        OffsetDateTime registeredAt,
        OffsetDateTime lastSeenAt,
        String sessionState,
        String currentRegion,
        String currentTaskKey,
        String currentTaskType,
        boolean isAlive) {
}
