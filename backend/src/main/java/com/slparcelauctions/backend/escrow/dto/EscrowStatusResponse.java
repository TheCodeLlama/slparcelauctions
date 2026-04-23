package com.slparcelauctions.backend.escrow.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
        Long escrowId,
        Long auctionId,
        EscrowState state,
        Long finalBidAmount,
        Long commissionAmt,
        Long payoutAmt,
        OffsetDateTime paymentDeadline,
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
