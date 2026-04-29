package com.slparcelauctions.backend.review.broadcast;

import com.slparcelauctions.backend.review.ReviewRevealedEnvelope;

/**
 * Abstraction over the WebSocket broadcast layer for review reveal events.
 * The production implementation is {@link StompReviewBroadcastPublisher}; a
 * no-op fallback ({@link NoOpReviewBroadcastPublisher}) steps aside when
 * the Stomp bean is on the classpath. Mirrors the
 * {@code EscrowBroadcastPublisher} / {@code AuctionBroadcastPublisher}
 * separation so review broadcasts can be asserted in isolation by
 * {@code @MockitoBean} in slice tests without dragging escrow + auction
 * publish surfaces.
 *
 * <p>Every publish method is safe to invoke from a
 * {@code TransactionSynchronization.afterCommit} callback — the review
 * row is already durable by the time we reach here, so a broker failure
 * is best-effort degradation (clients re-fetch on reconnect) rather than
 * data loss.
 */
public interface ReviewBroadcastPublisher {

    /**
     * Publishes a {@link ReviewRevealedEnvelope} to
     * {@code /topic/auction/{auctionId}} after the reveal transaction
     * commits. Called by {@code ReviewService.reveal} (both the
     * simultaneous-submit path and the day-14 scheduler path) via an
     * {@code afterCommit} callback so subscribers never observe a reveal
     * that rolls back on a late DB failure.
     */
    void publishReviewRevealed(ReviewRevealedEnvelope envelope);
}
