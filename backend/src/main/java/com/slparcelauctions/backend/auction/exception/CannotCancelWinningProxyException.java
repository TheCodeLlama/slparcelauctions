package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code ProxyBidService.cancelProxy} when the caller's proxy owns
 * the current winning bid on the auction. Cancelling a winning proxy would
 * leave the auction's {@code currentBidderId} pointing at a
 * {@link com.slparcelauctions.backend.auction.ProxyBidStatus#CANCELLED} proxy,
 * which breaks the invariant that every cancellation is either unobservable
 * (not currently winning) or followed by a full resolution-reopen flow. The
 * clean path for a winning owner who wants out is to let the auction end —
 * Phase 1 deliberately does not support mid-auction winner withdrawal.
 *
 * <p>Mapped to {@code 409 CANNOT_CANCEL_WINNING_PROXY}.
 */
public class CannotCancelWinningProxyException extends RuntimeException {

    public CannotCancelWinningProxyException() {
        super("Cannot cancel a proxy bid while it is currently winning the auction.");
    }
}
