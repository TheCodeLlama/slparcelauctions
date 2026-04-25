package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/sl/penalty-lookup} — the in-world terminal
 * asks "does this avatar owe a cancellation penalty?". Spec §7.5.
 *
 * <p>{@code slAvatarUuid} is the avatar that walked up to the terminal;
 * {@code terminalId} is the terminal's own object UUID, recorded in logs
 * for forensics but not stored on a ledger row (lookup is read-only).
 */
public record PenaltyLookupRequest(
        @NotNull UUID slAvatarUuid,
        @NotBlank String terminalId
) {}
