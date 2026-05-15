package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/realty/groups/{publicId}/wallet/deposit.
 * Spec §4.1.
 *
 * <p>Member-initiated personal-wallet → group-wallet transfer. The route
 * is JWT-gated and requires {@code DEPOSIT_TO_GROUP_WALLET} (leader
 * auto-passes). {@code memo} is an optional free-form note appended to
 * both ledger rows' {@code description}; {@code idempotencyKey} is
 * required and deduplicates retries (a duplicate key returns the original
 * ledger ids and current availabilities without re-debiting).
 */
public record GroupDepositRequest(
        @NotNull @Min(1) Long amount,
        @Size(max = 200) String memo,
        @NotNull UUID idempotencyKey) {
}
