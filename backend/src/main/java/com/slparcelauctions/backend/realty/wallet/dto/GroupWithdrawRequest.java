package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for POST /api/v1/realty/groups/{publicId}/wallet/withdraw.
 * Spec §5.3 / §5.6; recipient field added in sub-project G §7.3.
 *
 * <p>No backward-compat default. The {@code recipient} field is required
 * ({@code @NotNull}). SLParcels is pre-launch with no production clients,
 * so all callers update atomically with the G PR.
 */
public record GroupWithdrawRequest(
        @Positive long amount,
        @NotNull UUID idempotencyKey,
        @NotNull GroupWithdrawRecipient recipient) {
}
