package com.slparcelauctions.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReinstateAuctionRequest(
    @NotBlank @Size(max = 1000) String notes
) {}
