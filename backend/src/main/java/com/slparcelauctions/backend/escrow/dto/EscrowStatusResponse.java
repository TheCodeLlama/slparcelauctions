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
        List<EscrowTimelineEntry> timeline,
        /**
         * Hard-gate timestamp. Null = Set-Sell-To sub-phase; set =
         * Buy-Parcel sub-phase (spec §3). Derived sub-phase drives the
         * frontend stepper + state cards.
         */
        OffsetDateTime sellToConfirmedAt,
        /**
         * Last definitive bot Set-Sell-To outcome for seller-facing copy
         * (e.g. {@code SELL_TO_NOT_SET} / {@code WRONG_BUYER} /
         * {@code PRICE_NOT_ZERO}). Null until a definitive negative.
         */
        String sellToLastResult,
        /** Seller manual Set-Sell-To attempts remaining (cap - consumed, floored at 0). */
        Integer sellToVerifyAttemptsRemaining,
        /** Seller manual Buy-Parcel attempts remaining. */
        Integer buyVerifySellerAttemptsRemaining,
        /** Buyer manual Buy-Parcel attempts remaining. */
        Integer buyVerifyBuyerAttemptsRemaining,
        /** null | "OPEN" | "RESOLVED" | "DISMISSED" — status of the escrow's review, if any. */
        String manualReviewStatus,
        /** null | "SET_SELL_TO" | "BUY_PARCEL" — step the review was opened against. */
        String manualReviewStep,
        /** Clickable SL map URL to the parcel (https maps.secondlife.com). */
        String parcelMapUrl,
        /** In-world viewer teleport SLURL to the parcel (secondlife:///app/teleport). */
        String parcelViewerUrl) { }
