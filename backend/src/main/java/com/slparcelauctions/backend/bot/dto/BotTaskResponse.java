package com.slparcelauctions.backend.bot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;

/**
 * Bot queue projection returned by {@code GET /api/v1/bot/tasks/pending} and
 * the {@code PUT /api/v1/bot/tasks/{id}} callback. Excludes internal-only
 * fields like {@code resultData} and {@code lastUpdatedAt} — bot workers do
 * not need them.
 */
public record BotTaskResponse(
        Long id,
        BotTaskType taskType,
        BotTaskStatus status,
        Long auctionId,
        UUID parcelUuid,
        String regionName,
        Long sentinelPrice,
        UUID assignedBotUuid,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {

    public static BotTaskResponse from(BotTask t) {
        return new BotTaskResponse(
                t.getId(),
                t.getTaskType(),
                t.getStatus(),
                t.getAuction().getId(),
                t.getParcelUuid(),
                t.getRegionName(),
                t.getSentinelPrice(),
                t.getAssignedBotUuid(),
                t.getFailureReason(),
                t.getCreatedAt(),
                t.getCompletedAt());
    }
}
