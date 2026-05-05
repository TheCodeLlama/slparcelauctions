package com.slparcelauctions.backend.admin.users.wallet.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminWalletNotesRequest(
    @NotBlank String notes
) {}
