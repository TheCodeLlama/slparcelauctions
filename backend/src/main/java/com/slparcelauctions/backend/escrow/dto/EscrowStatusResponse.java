package com.slparcelauctions.backend.escrow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.slparcelauctions.backend.escrow.EscrowState;

/**
 * Response DTO for {@code GET /api/v1/auctions/{id}/escrow} and the
 * {@code POST /dispute} echo. Mirrors the escrow-row column set plus a
 * computed {@code timeline} derived from state-column timestamps and
 * ledger rows. Visible only to seller or winner (access enforcement in
 * {@link com.slparcelauctions.backend.escrow.EscrowService#getStatus}).
 * Spec §4 / §8.
 */
public record EscrowStatusResponse(
        UUID escrowPublicId,
        UUID auctionPublicId,
        /**
         * Winner's SL avatar name. Shown to the seller in the
         * TRANSFER_PENDING card so they can paste it into the SL viewer's
         * About Land → Sell Land → "Sell to" field. Null only for
         * pre-FUNDED states (no winner resolved yet) — should be present
         * whenever an escrow row exists.
         */
        String winnerSlAvatarName,
        EscrowState state,
        Long finalBidAmount,
        Long commissionAmt,
        Long payoutAmt,
        /**
         * Transfer deadline = fundedAt + 72h. Populated once the escrow
         * transitions through FUNDED → TRANSFER_PENDING. The retired
         * paymentDeadline column from before the wallet-only escrow spec
         * is no longer projected.
         */
        OffsetDateTime transferDeadline,
        OffsetDateTime fundedAt,
        OffsetDateTime transferConfirmedAt,
        OffsetDateTime completedAt,
        OffsetDateTime disputedAt,
        OffsetDateTime frozenAt,
        OffsetDateTime expiredAt,
        String disputeReasonCategory,
        String disputeDescription,
        String freezeReason,
        List<EscrowTimelineEntry> timeline) { }
