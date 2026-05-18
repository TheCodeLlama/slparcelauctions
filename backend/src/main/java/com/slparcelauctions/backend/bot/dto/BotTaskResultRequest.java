package com.slparcelauctions.backend.bot.dto;

import com.slparcelauctions.backend.bot.SellToOutcome;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Bot {@code VERIFY_SELL_TO} result callback body (spec §5.1). Posted by the
 * .NET worker to {@code POST /api/v1/bot/tasks/{taskId}/result}. This is the
 * <b>frozen bot wire contract</b> — Phase 7 mirrors it field-for-field. Do
 * not rename fields.
 */
public record BotTaskResultRequest(
        @NotNull SellToOutcome outcome,
        UUID observedOwnerUuid,
        UUID observedAuthBuyerUuid,
        Long observedSalePrice,
        Boolean observedForSale) {}
