package com.slparcelauctions.backend.wallet.me;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PayPenaltyApiRequest(
        @NotNull @Min(1) Long amount,
        @NotBlank @Size(max = 64) String idempotencyKey
) { }
