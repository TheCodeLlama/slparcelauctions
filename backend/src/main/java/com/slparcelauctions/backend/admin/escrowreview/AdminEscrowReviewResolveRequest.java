package com.slparcelauctions.backend.admin.escrowreview;

import com.slparcelauctions.backend.escrow.review.ManualReviewResolution;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminEscrowReviewResolveRequest(
        @NotNull ManualReviewResolution action,
        @NotNull @Size(min = 1, max = 500) String adminNote) {
}
