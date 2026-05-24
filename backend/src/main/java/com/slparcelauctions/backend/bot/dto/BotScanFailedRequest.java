package com.slparcelauctions.backend.bot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for POST /api/v1/bot/tasks/{taskId}/scan-failed.
 * Carries a short machine-readable reason string (e.g. "TERRAIN_NOT_LOADED")
 * that the bot reports when a SCAN_PARCEL task cannot complete.
 */
public record BotScanFailedRequest(@NotBlank String reason) {}
