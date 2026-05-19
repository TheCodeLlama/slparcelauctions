package com.slparcelauctions.backend.bot.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.escrow.EscrowManualActionService;

/**
 * Bot queue projection returned by the claim endpoint. After the
 * ownership-only verification refactor (spec 2026-05-16) the
 * MONITOR_AUCTION-specific expected_auth_buyer_uuid +
 * expected_sale_price_lindens fields are gone; the remaining
 * historical-shape fields stay for forensic compatibility with the bot
 * worker's wire contract.
 *
 * <p><b>expectedPreTransferUuid / expectedOwnerType</b> ride out alongside
 * the historical fields for the bot's VERIFY_BUY_OWNER classification.
 * Pre-transfer UUID re-uses the historical {@code expected_seller_uuid}
 * column (individual sale = seller's avatar UUID, group sale = registered SL group UUID);
 * ownerType comes from the {@code resultData} JSON payload key
 * {@link EscrowManualActionService#EXPECTED_OWNER_TYPE_KEY}.
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
        UUID expectedWinnerUuid,
        UUID expectedSellerUuid,
        UUID expectedPreTransferUuid,
        String expectedOwnerType,
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
                t.getExpectedWinnerUuid(),
                t.getExpectedSellerUuid(),
                t.getExpectedSellerUuid(),
                extractExpectedOwnerType(t),
                t.getExpectedMaxSalePriceLindens(),
                t.getAssignedBotUuid(),
                t.getFailureReason(),
                t.getNextRunAt(),
                t.getRecurrenceIntervalSeconds(),
                t.getCreatedAt(),
                t.getCompletedAt());
    }

    private static String extractExpectedOwnerType(BotTask t) {
        if (t.getResultData() == null) {
            return null;
        }
        Object v = t.getResultData().get(EscrowManualActionService.EXPECTED_OWNER_TYPE_KEY);
        return v == null ? null : v.toString();
    }
}
