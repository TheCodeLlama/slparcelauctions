package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code ProxyBidService.updateProxyMax} when the caller's proxy is
 * in a state that cannot be updated — currently the only such state is
 * {@link com.slparcelauctions.backend.auction.ProxyBidStatus#CANCELLED}. An
 * {@code EXHAUSTED} proxy can be resurrected by raising the max, so it does
 * <strong>not</strong> map to this exception; {@code CANCELLED} is terminal.
 *
 * <p>Mapped to {@code 409 INVALID_PROXY_STATE}. The handler surfaces the
 * reason string so the client can render a targeted remediation prompt
 * ("create a new proxy instead").
 */
public class InvalidProxyStateException extends RuntimeException {

    private final String reason;

    public InvalidProxyStateException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
