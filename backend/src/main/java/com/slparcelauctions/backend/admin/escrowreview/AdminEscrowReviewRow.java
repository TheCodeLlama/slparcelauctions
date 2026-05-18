package com.slparcelauctions.backend.admin.escrowreview;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewRole;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;

/**
 * List-row projection for the admin escrow manual-review queue. Mirrors the
 * field spirit of {@code AdminDisputeQueueRow}: public ids only on the wire,
 * the parcel name for at-a-glance triage, and a pre-computed age in minutes.
 */
public record AdminEscrowReviewRow(
        UUID reviewPublicId,
        UUID escrowPublicId,
        UUID auctionPublicId,
        String parcelName,
        ManualReviewStep step,
        ManualReviewReason reason,
        ManualReviewStatus status,
        ManualReviewRole requestedRole,
        OffsetDateTime createdAt,
        long ageMinutes) {
}
