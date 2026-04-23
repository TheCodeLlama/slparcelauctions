package com.slparcelauctions.backend.auction.exception;

/**
 * Raised when {@code PUT /proxy-bid}, {@code DELETE /proxy-bid}, or
 * {@code GET /proxy-bid} looks up the caller's proxy on an auction and finds
 * no matching row. Mapped to {@code 404 PROXY_BID_NOT_FOUND}.
 *
 * <p>For {@code DELETE} the service additionally filters to
 * {@link com.slparcelauctions.backend.auction.ProxyBidStatus#ACTIVE} — cancelling
 * an already-EXHAUSTED or already-CANCELLED row is treated as not-found rather
 * than a distinct 409; the outcome is the same for the caller (nothing to cancel).
 */
public class ProxyBidNotFoundException extends RuntimeException {

    public ProxyBidNotFoundException() {
        super("No proxy bid exists for the caller on this auction.");
    }
}
