package com.slparcelauctions.backend.admin.infrastructure.bots;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public record BotHeartbeatRequest(
        @NotBlank String workerName,
        @NotBlank String slUuid,
        @NotBlank String sessionState,
        String currentRegion,
        String currentTaskKey,
        String currentTaskType,
        OffsetDateTime lastClaimAt) {
}
