package com.slparcelauctions.backend.auction.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised by the broker-cancel path when an auction does not satisfy the
 * preconditions for a broker-initiated cancellation (e.g. wrong case, wrong
 * status, no broker associated). {@code reason} is a short machine-readable
 * marker the exception handler can render into ProblemDetail extensions.
 */
@Getter
public class BrokerCancelNotApplicableException extends RuntimeException {
    public static final String CODE = "BROKER_CANCEL_NOT_APPLICABLE";

    private final UUID auctionPublicId;
    private final String reason;

    public BrokerCancelNotApplicableException(UUID auctionPublicId, String reason) {
        super("Broker cancel not applicable for auction " + auctionPublicId + ": " + reason);
        this.auctionPublicId = auctionPublicId;
        this.reason = reason;
    }
}
