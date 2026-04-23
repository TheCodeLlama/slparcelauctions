package com.slparcelauctions.backend.bot.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/v1/bot/tasks/claim. The worker identifies itself with
 * its SL avatar UUID so the backend can stamp {@code assignedBotUuid} on
 * the claimed row (useful for debug via GET /pending).
 */
public record BotTaskClaimRequest(@NotNull UUID botUuid) {}
