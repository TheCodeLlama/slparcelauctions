package com.slparcelauctions.backend.admin.disputes;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminDisputeResolveRequest(
        @NotNull AdminDisputeAction action,
        Boolean alsoCancelListing,
        @NotNull @Size(min = 1, max = 500) String adminNote) {
}
