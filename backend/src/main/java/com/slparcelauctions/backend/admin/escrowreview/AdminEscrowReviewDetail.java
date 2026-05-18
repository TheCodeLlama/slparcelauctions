package com.slparcelauctions.backend.admin.escrowreview;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewResolution;
import com.slparcelauctions.backend.escrow.review.ManualReviewRole;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;

/**
 * Detail projection for a single escrow manual review: the review row plus an
 * escrow snapshot and the observed bot/world-API evidence an admin needs to
 * decide a resolution (last sell-to result, per-step attempt counts, deadlines,
 * parcel name + SLURL).
 */
public record AdminEscrowReviewDetail(
        UUID reviewPublicId,
        UUID escrowPublicId,
        UUID auctionPublicId,
        String auctionTitle,
        String parcelName,
        String slurl,
        // Review row
        ManualReviewStep step,
        ManualReviewReason reason,
        ManualReviewStatus status,
        ManualReviewRole requestedRole,
        ManualReviewResolution resolution,
        String adminNotes,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        // Escrow snapshot + observed evidence
        EscrowState escrowState,
        long finalBidAmount,
        OffsetDateTime fundedAt,
        OffsetDateTime sellToConfirmedAt,
        OffsetDateTime transferConfirmedAt,
        OffsetDateTime transferDeadline,
        String sellToLastResult,
        OffsetDateTime sellToLastCheckedAt,
        int sellToVerifyAttempts,
        int buyVerifySellerAttempts,
        int buyVerifyBuyerAttempts,
        int consecutiveSellToBotFailures,
        int consecutiveWorldApiFailures) {
}
