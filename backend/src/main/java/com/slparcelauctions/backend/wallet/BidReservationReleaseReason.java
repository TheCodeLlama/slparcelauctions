package com.slparcelauctions.backend.wallet;

/**
 * Reason a {@link BidReservation} was released. Stamped at the moment
 * {@code released_at} is set.
 *
 * <p>See spec docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.3.
 */
public enum BidReservationReleaseReason {
    /**
     * The user was outbid by another bidder; their reservation was released
     * to make room for the new high bidder's reservation.
     */
    OUTBID,

    /**
     * The auction was cancelled by the seller (where allowed) or admin.
     */
    AUCTION_CANCELLED,

    /**
     * The auction was fraud-frozen by admin.
     */
    AUCTION_FRAUD_FREEZE,

    /**
     * Buy-It-Now closed the auction; non-winning bidders' reservations are
     * released. The BIN-clicker's own reservation (if any) is released with
     * {@link #ESCROW_FUNDED} since their L$ flowed into escrow.
     */
    AUCTION_BIN_ENDED,

    /**
     * Auction-end auto-fund consumed this reservation: the winner's L$ was
     * debited from balance and the escrow row was created in FUNDED state.
     */
    ESCROW_FUNDED,

    /**
     * The user was banned by admin; all their active reservations are
     * released.
     */
    USER_BANNED
}
