package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code ProxyBidService.updateProxyMax} when the caller's proposed
 * {@code newMax} violates the update constraints:
 *
 * <ul>
 *   <li>ACTIVE+winning branch: {@code newMax} must be strictly greater than
 *       {@code auction.currentBid}.</li>
 *   <li>EXHAUSTED resurrection branch: {@code newMax} must be strictly greater
 *       than the proxy's existing {@code maxAmount} (resurrection is increase-only).</li>
 * </ul>
 *
 * <p>Mapped to {@code 400 INVALID_PROXY_MAX}. Distinct from
 * {@link BidTooLowException} which covers the "below minimum increment floor"
 * case — that's reserved for first-time placement validation.
 */
public class InvalidProxyMaxException extends RuntimeException {

    private final String reason;

    public InvalidProxyMaxException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
