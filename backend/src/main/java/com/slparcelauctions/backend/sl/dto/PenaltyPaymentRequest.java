package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/sl/penalty-payment} — the in-world terminal
 * posts after pulling L$ from the seller's avatar. Spec §7.6.
 *
 * <p>{@code slTransactionId} is the SL grid's own transaction id, used as
 * the idempotency key on the {@code escrow_transactions} ledger so a
 * benign terminal-side retry returns the current balance instead of
 * double-debiting. {@code amount} is the L$ paid (partial payments are
 * allowed; overpayment beyond the outstanding balance returns 422).
 */
public record PenaltyPaymentRequest(
        @NotNull UUID slAvatarUuid,
        @NotBlank String slTransactionId,
        @NotNull @Min(1) Long amount,
        @NotBlank String terminalId
) {}
