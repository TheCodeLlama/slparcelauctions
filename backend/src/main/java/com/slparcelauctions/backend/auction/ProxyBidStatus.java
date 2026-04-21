package com.slparcelauctions.backend.auction;

/**
 * Lifecycle state of a {@link ProxyBid}.
 *
 * <p>Enforced by the partial unique index {@code proxy_bids_one_active_per_user}
 * — at most one {@code ACTIVE} row per {@code (auction_id, user_id)}. See
 * {@link com.slparcelauctions.backend.auction.config.ProxyBidPartialUniqueIndexInitializer}.
 */
public enum ProxyBidStatus {
    /** Still able to emit automatic bids up to {@code maxAmount}. */
    ACTIVE,

    /** Hit its {@code maxAmount} ceiling and can no longer out-bid. */
    EXHAUSTED,

    /** Bidder withdrew the proxy before it exhausted. */
    CANCELLED
}
