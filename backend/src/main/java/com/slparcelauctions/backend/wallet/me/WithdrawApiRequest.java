package com.slparcelauctions.backend.wallet.me;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/me/wallet/withdraw}. Recipient is NEVER in
 * the body — the backend pins it to {@code user.slAvatarUuid}.
 */
public record WithdrawApiRequest(
        @NotNull @Min(1) Long amount,
        @NotBlank @Size(max = 64) String idempotencyKey
) { }
