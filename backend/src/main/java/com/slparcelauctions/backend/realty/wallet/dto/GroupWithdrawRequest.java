package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for POST /api/v1/realty/groups/{publicId}/wallet/withdraw.
 * Spec §5.3 / §5.6.
 */
public record GroupWithdrawRequest(
        @Positive long amount,
        @NotNull UUID idempotencyKey) {
}
