package com.slparcelauctions.backend.escrow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SellerEvidenceRequest(
        @NotNull @Size(min = 10, max = 2000) String text) {
}
