package com.slparcelauctions.backend.auction.broadcast;

import java.time.Clock;
import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.user.User;

/**
 * WebSocket payload broadcast to {@code /topic/auction/{auctionId}} when the
 * auction transitions to {@code ENDED} — either via the scheduler close path
 * or inline via buy-it-now. Task 2 introduced the record as a stub; Task 3
 * adds the {@link #of(Auction, User, Clock)} factory used by the inline
 * buy-it-now close path in {@code BidService}. The scheduler close path in
 * Task 6 reuses the same factory shape; the production publisher in Task 5
 * consumes the record directly.
 *
 * <p>The {@code type} discriminator is hard-wired to {@code "AUCTION_ENDED"}
 * so client-side STOMP subscriptions can route it alongside
 * {@link BidSettlementEnvelope} from a single topic subscription.
 */
public record AuctionEndedEnvelope(
        String type,
        Long auctionId,
        OffsetDateTime serverTime,
        OffsetDateTime endsAt,
        AuctionEndOutcome endOutcome,
        Long finalBid,
        Long winnerUserId,
        String winnerDisplayName,
        Integer bidCount) {

    /**
     * Builds an envelope from a fully-mutated in-memory auction plus the
     * resolved winning {@link User}. {@code winner} may be {@code null} on
     * paths where the auction ended without a bidder (e.g. {@code NO_BIDS} /
     * {@code RESERVE_NOT_MET} closures in Task 6) — the display name is
     * elided in that case. For the buy-it-now inline close in Task 3 the
     * winner is always the last emitted bid's bidder, which the service
     * layer already has resolved.
     */
    public static AuctionEndedEnvelope of(Auction auction, User winner, Clock clock) {
        return new AuctionEndedEnvelope(
                "AUCTION_ENDED",
                auction.getId(),
                OffsetDateTime.now(clock),
                auction.getEndsAt(),
                auction.getEndOutcome(),
                auction.getFinalBidAmount(),
                auction.getWinnerUserId(),
                winner == null ? null : winner.getDisplayName(),
                auction.getBidCount());
    }
}
