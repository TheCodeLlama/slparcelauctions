package com.slparcelauctions.backend.admin.users.wallet.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminWalletForgivePenaltyRequest(
    @NotNull @Min(1) Long amount,
    @NotBlank String notes
) {}
