package com.slparcelauctions.backend.auction.broadcast;

import com.slparcelauctions.backend.auction.dto.AuctionCancelledEnvelope;

/**
 * Abstraction over the WebSocket broadcast layer. Bid-placement, proxy, and
 * auction-end services depend on this interface and never touch STOMP
 * directly — the production implementation is injected in Task 5 and the
 * Task 2 default is a logging no-op. Keeping it behind an interface also
 * lets tests inject a capturing fake to assert envelope contents and
 * ordering without standing up a WebSocket broker.
 *
 * <p>Implementations must be safe to invoke from a
 * {@code TransactionSynchronization.afterCommit} callback — they never run
 * inside the transaction and must tolerate being called after the
 * persistence context has closed.
 */
public interface AuctionBroadcastPublisher {

    /**
     * Publishes a {@link BidSettlementEnvelope} for a post-commit bid
     * transaction. Called once per committed placement, regardless of how
     * many bid rows the transaction emitted (manual + proxy counter is still
     * a single settlement).
     */
    void publishSettlement(BidSettlementEnvelope envelope);

    /**
     * Publishes an {@link AuctionEndedEnvelope} when the auction transitions
     * to a terminal state. Emitted by the scheduler close path, the buy-it-
     * now inline-close path, and ownership-based suspension/cancel paths
     * that end the auction. Never called from Task 2's core bid path.
     */
    void publishEnded(AuctionEndedEnvelope envelope);

    /**
     * Publishes an {@link AuctionCancelledEnvelope} when an auction
     * transitions to {@code CANCELLED}. Invoked from
     * {@code CancellationService.cancel}'s afterCommit callback so
     * subscribers never observe a cancellation that rolls back. Closes the
     * deferred-ledger "Cancellation WS broadcast" entry (Epic 08 sub-spec 2).
     */
    void publishCancelled(AuctionCancelledEnvelope envelope);
}
