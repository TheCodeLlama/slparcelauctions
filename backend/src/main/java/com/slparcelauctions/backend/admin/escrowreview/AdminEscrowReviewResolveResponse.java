package com.slparcelauctions.backend.admin.escrowreview;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.review.ManualReviewResolution;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;

public record AdminEscrowReviewResolveResponse(
        UUID reviewPublicId,
        ManualReviewStatus newStatus,
        ManualReviewResolution resolution,
        OffsetDateTime resolvedAt) {
}
