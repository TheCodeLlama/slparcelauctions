package com.slparcelauctions.backend.bot.dto;

import com.slparcelauctions.backend.bot.BuyOwnerOutcome;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Bot {@code VERIFY_BUY_OWNER} result callback body. Posted by the .NET worker
 * to {@code POST /api/v1/bot/tasks/{taskId}/verify-buy-owner-result}. This is
 * the <b>frozen bot wire contract</b> — the bot mirrors it field-for-field.
 * Do not rename fields.
 *
 * @param outcome           classification of the live owner observation
 * @param observedOwnerUuid the parcel owner the bot observed (best-effort —
 *                          null for {@code PARCEL_DELETED} / transient
 *                          failures). Logged into the fraud-flag evidence for
 *                          freeze outcomes.
 * @param observedOwnerType "agent" or "group" (best-effort, null on failure).
 *                          Lets admins distinguish "stranger" from "third-party
 *                          group" at a glance in the fraud queue.
 */
public record BuyOwnerResultRequest(
        @NotNull BuyOwnerOutcome outcome,
        UUID observedOwnerUuid,
        String observedOwnerType) {}
