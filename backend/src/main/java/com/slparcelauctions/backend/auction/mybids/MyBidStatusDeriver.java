package com.slparcelauctions.backend.auction.mybids;

import java.util.Objects;

import com.slparcelauctions.backend.auction.Auction;

/**
 * Pure function that derives a caller-relative {@link MyBidStatus} from an
 * {@link Auction}'s live state. Implements the mapping in spec §10, updated
 * after the 2026-05-17 status state-machine rewire (the old {@code ENDED}
 * status fanned out across {@code TRANSFER_PENDING}, {@code DISPUTED},
 * {@code COMPLETED}, {@code EXPIRED}, {@code FROZEN}):
 *
 * <pre>
 *   ACTIVE                        + caller is current bidder         → WINNING
 *   ACTIVE                        + someone else is current bidder   → OUTBID
 *   TRANSFER_PENDING / DISPUTED   + caller is winner                 → WON
 *   TRANSFER_PENDING / DISPUTED   + someone else won                 → LOST
 *   COMPLETED                     + caller is winner                 → WON
 *   COMPLETED                     + someone else won                 → LOST
 *   EXPIRED (endOutcome=SOLD/BOUGHT_NOW, escrow timed out)
 *                                 + caller was winner                → LOST (they won the bid but lost the parcel)
 *   EXPIRED (endOutcome=RESERVE_NOT_MET) + caller was high bidder    → RESERVE_NOT_MET
 *   EXPIRED (endOutcome=RESERVE_NOT_MET) + someone else was high     → LOST
 *   EXPIRED (endOutcome=NO_BIDS)                                     → LOST (defensive — caller must have bid)
 *   EXPIRED (null endOutcome)                                        → LOST (defensive)
 *   FROZEN  + caller was winner                                       → LOST (won the bid; escrow frozen for fraud)
 *   FROZEN  + someone else                                           → LOST
 *   CANCELLED                                                        → CANCELLED
 *   SUSPENDED                                                        → SUSPENDED
 *   anything else                                                    → LOST (defensive)
 * </pre>
 *
 * <p>Note: the existing {@link MyBidStatus} enum has no dedicated
 * "won-but-escrow-failed" value, so winners whose escrow ended in
 * {@code EXPIRED} or {@code FROZEN} surface as {@code LOST}. If product
 * decides those deserve their own bucket on the dashboard, add a new enum
 * value (e.g. {@code WON_THEN_LOST}) — don't reuse {@code WON} for these
 * because the parcel never transferred.
 *
 * <p>The "defensive" branches are unreachable in normal operation — this
 * deriver is only called for auctions the caller has actually bid on, so
 * {@code NO_BIDS} cannot arise, and any post-ACTIVE row should have
 * {@code endOutcome} set. Returning {@code LOST} keeps the dashboard
 * well-formed instead of throwing.
 */
public final class MyBidStatusDeriver {

    private MyBidStatusDeriver() {
        // utility class — do not instantiate.
    }

    public static MyBidStatus derive(Long userId, Auction a) {
        return switch (a.getStatus()) {
            case ACTIVE -> Objects.equals(a.getCurrentBidderId(), userId)
                    ? MyBidStatus.WINNING
                    : MyBidStatus.OUTBID;
            case TRANSFER_PENDING, DISPUTED, COMPLETED -> Objects.equals(a.getWinnerUserId(), userId)
                    ? MyBidStatus.WON
                    : MyBidStatus.LOST;
            case EXPIRED -> deriveExpired(userId, a);
            case FROZEN -> MyBidStatus.LOST;
            case CANCELLED -> MyBidStatus.CANCELLED;
            case SUSPENDED -> MyBidStatus.SUSPENDED;
            default -> MyBidStatus.LOST; // defensive — DRAFT / VERIFICATION_* shouldn't surface to bidders.
        };
    }

    private static MyBidStatus deriveExpired(Long userId, Auction a) {
        if (a.getEndOutcome() == null) {
            return MyBidStatus.LOST; // defensive
        }
        return switch (a.getEndOutcome()) {
            // Escrow transfer-deadline timeout on a SOLD/BOUGHT_NOW close: the
            // winner won the bid but lost the parcel. No dedicated enum yet —
            // collapse to LOST.
            case SOLD, BOUGHT_NOW -> MyBidStatus.LOST;
            case RESERVE_NOT_MET -> Objects.equals(a.getCurrentBidderId(), userId)
                    ? MyBidStatus.RESERVE_NOT_MET
                    : MyBidStatus.LOST;
            case NO_BIDS -> MyBidStatus.LOST; // unreachable in practice
        };
    }
}
