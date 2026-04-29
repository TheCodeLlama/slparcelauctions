package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record WithdrawalRequest(
        @NotNull @Positive Long amount,
        @NotBlank String recipientUuid,
        @Size(max = 1000) String notes) {
}
