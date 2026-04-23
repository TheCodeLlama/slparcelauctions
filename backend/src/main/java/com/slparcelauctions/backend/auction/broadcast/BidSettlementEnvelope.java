package com.slparcelauctions.backend.auction.broadcast;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.dto.BidHistoryEntry;
import com.slparcelauctions.backend.user.User;

/**
 * WebSocket payload broadcast to {@code /topic/auction/{auctionId}} after a
 * bid transaction commits. Carries the post-commit aggregate state plus the
 * 1-or-2 bid rows the transaction emitted so clients can render an updated
 * history without a round-trip {@code GET}.
 *
 * <p>Task 2 introduces the envelope record as a stub — the real publisher
 * implementation (STOMP wiring) lands in Task 5. The factory method and
 * shape are stable so Task 5 only has to swap in the delivery mechanism.
 *
 * <p>The {@code type} discriminator is hard-wired to {@code "BID_SETTLEMENT"}
 * so client-side STOMP subscriptions can route both envelope shapes from a
 * single topic subscription.
 */
public record BidSettlementEnvelope(
        String type,
        Long auctionId,
        OffsetDateTime serverTime,
        Long currentBid,
        Long currentBidderId,
        String currentBidderDisplayName,
        Integer bidCount,
        OffsetDateTime endsAt,
        OffsetDateTime originalEndsAt,
        List<BidHistoryEntry> newBids) {

    /**
     * Builds an envelope from a fully-mutated in-memory auction + its just-
     * emitted bid rows. {@code currentTopBidder} is passed in from the
     * service layer rather than dereferenced off {@code Auction.getSeller()}
     * etc. because {@code Auction.currentBidderId} is a plain {@code Long}
     * (no {@code @ManyToOne}) and the service already has the resolved
     * {@link User} in hand.
     */
    public static BidSettlementEnvelope of(
            Auction auction, List<Bid> emitted, User currentTopBidder, Clock clock) {
        List<BidHistoryEntry> entries = emitted.stream()
                .map(BidHistoryEntry::from)
                .toList();
        return new BidSettlementEnvelope(
                "BID_SETTLEMENT",
                auction.getId(),
                OffsetDateTime.now(clock),
                auction.getCurrentBid(),
                auction.getCurrentBidderId(),
                currentTopBidder == null ? null : currentTopBidder.getDisplayName(),
                auction.getBidCount(),
                auction.getEndsAt(),
                auction.getOriginalEndsAt(),
                entries);
    }
}
