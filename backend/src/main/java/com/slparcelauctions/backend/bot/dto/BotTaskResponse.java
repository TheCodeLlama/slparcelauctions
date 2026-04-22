package com.slparcelauctions.backend.bot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;

/**
 * Bot queue projection returned by the claim endpoint. Exposes monitor
 * fields (escrowId + expected* + positions + nextRunAt) so the worker has
 * everything it needs for a one-shot decision without follow-up lookups.
 * Excludes resultData and lastUpdatedAt — bot workers do not need them.
 */
public record BotTaskResponse(
        Long id,
        BotTaskType taskType,
        BotTaskStatus status,
        Long auctionId,
        Long escrowId,
        UUID parcelUuid,
        String regionName,
        Double positionX,
        Double positionY,
        Double positionZ,
        Long sentinelPrice,
        UUID expectedOwnerUuid,
        UUID expectedAuthBuyerUuid,
        Long expectedSalePriceLindens,
        UUID expectedWinnerUuid,
        UUID expectedSellerUuid,
        Long expectedMaxSalePriceLindens,
        UUID assignedBotUuid,
        String failureReason,
        OffsetDateTime nextRunAt,
        Integer recurrenceIntervalSeconds,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {

    public static BotTaskResponse from(BotTask t) {
        return new BotTaskResponse(
                t.getId(),
                t.getTaskType(),
                t.getStatus(),
                t.getAuction().getId(),
                t.getEscrow() == null ? null : t.getEscrow().getId(),
                t.getParcelUuid(),
                t.getRegionName(),
                t.getPositionX(),
                t.getPositionY(),
                t.getPositionZ(),
                t.getSentinelPrice(),
                t.getExpectedOwnerUuid(),
                t.getExpectedAuthBuyerUuid(),
                t.getExpectedSalePriceLindens(),
                t.getExpectedWinnerUuid(),
                t.getExpectedSellerUuid(),
                t.getExpectedMaxSalePriceLindens(),
                t.getAssignedBotUuid(),
                t.getFailureReason(),
                t.getNextRunAt(),
                t.getRecurrenceIntervalSeconds(),
                t.getCreatedAt(),
                t.getCompletedAt());
    }
}
