package com.slparcelauctions.backend.bot.dto;

import java.util.UUID;

import com.slparcelauctions.backend.bot.MonitorOutcome;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/bot/tasks/{id}/monitor. {@code outcome} is required;
 * the {@code observed*} fields and {@code note} are contextual — the
 * backend records them in {@code BotTask.resultData} for audit.
 */
public record BotMonitorResultRequest(
        @NotNull MonitorOutcome outcome,
        UUID observedOwner,
        UUID observedAuthBuyer,
        Long observedSalePrice,
        @Size(max = 500) String note) {}
