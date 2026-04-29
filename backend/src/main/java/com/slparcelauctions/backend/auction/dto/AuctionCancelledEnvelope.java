package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.Auction;

/**
 * WebSocket payload broadcast to {@code /topic/auction/{auctionId}} when an
 * auction transitions to {@code CANCELLED}. Closes the deferred-ledger
 * "Cancellation WS broadcast" entry by folding {@code AUCTION_CANCELLED} into
 * Epic 08 sub-spec 2. Subscribers route on {@code type} alongside
 * {@code BID_SETTLEMENT} / {@code AUCTION_ENDED} / {@code REVIEW_REVEALED}
 * from a single subscription.
 *
 * <p>The publisher is invoked from a
 * {@code TransactionSynchronization.afterCommit} callback registered inside
 * {@code CancellationService.cancel}, so subscribers never observe a
 * cancellation that rolls back on a late DB failure.
 */
public record AuctionCancelledEnvelope(
        String type,
        Long auctionId,
        OffsetDateTime cancelledAt,
        Boolean hadBids) {

    public static AuctionCancelledEnvelope of(Auction auction, boolean hadBids, OffsetDateTime cancelledAt) {
        return new AuctionCancelledEnvelope(
                "AUCTION_CANCELLED",
                auction.getId(),
                cancelledAt,
                hadBids);
    }
}
