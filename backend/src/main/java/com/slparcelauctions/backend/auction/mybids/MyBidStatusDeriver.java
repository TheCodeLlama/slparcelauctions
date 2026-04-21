package com.slparcelauctions.backend.auction.mybids;

import java.util.Objects;

import com.slparcelauctions.backend.auction.Auction;

/**
 * Pure function that derives a caller-relative {@link MyBidStatus} from an
 * {@link Auction}'s live state. Implements the mapping in spec §10:
 *
 * <pre>
 *   ACTIVE     + caller is current bidder           → WINNING
 *   ACTIVE     + someone else is current bidder     → OUTBID
 *   ENDED/SOLD | BOUGHT_NOW + caller is winner      → WON
 *   ENDED/SOLD | BOUGHT_NOW + someone else won      → LOST
 *   ENDED/RESERVE_NOT_MET + caller was high bidder  → RESERVE_NOT_MET
 *   ENDED/RESERVE_NOT_MET + someone else was high   → LOST
 *   ENDED/NO_BIDS                                    → LOST (defensive — caller must have bid)
 *   ENDED with null endOutcome                       → LOST (defensive)
 *   CANCELLED                                        → CANCELLED
 *   SUSPENDED                                        → SUSPENDED
 *   anything else                                    → LOST (defensive)
 * </pre>
 *
 * <p>The "defensive" branches are unreachable in normal operation — this
 * deriver is only called for auctions the caller has actually bid on, so
 * {@code NO_BIDS} cannot arise, and a lifecycle that reaches {@code ENDED}
 * without {@code endOutcome} set would be a data bug. Returning {@code LOST}
 * keeps the dashboard well-formed instead of throwing.
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
            case ENDED -> deriveEnded(userId, a);
            case CANCELLED -> MyBidStatus.CANCELLED;
            case SUSPENDED -> MyBidStatus.SUSPENDED;
            default -> MyBidStatus.LOST; // defensive — unexpected transitional state.
        };
    }

    private static MyBidStatus deriveEnded(Long userId, Auction a) {
        if (a.getEndOutcome() == null) {
            return MyBidStatus.LOST; // defensive
        }
        return switch (a.getEndOutcome()) {
            case SOLD, BOUGHT_NOW -> Objects.equals(a.getWinnerUserId(), userId)
                    ? MyBidStatus.WON
                    : MyBidStatus.LOST;
            case RESERVE_NOT_MET -> Objects.equals(a.getCurrentBidderId(), userId)
                    ? MyBidStatus.RESERVE_NOT_MET
                    : MyBidStatus.LOST;
            case NO_BIDS -> MyBidStatus.LOST; // unreachable in practice
        };
    }
}
