package com.slparcelauctions.backend.wallet.me;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PayListingFeeRequest(
        @NotBlank @Size(max = 64) String idempotencyKey
) { }
