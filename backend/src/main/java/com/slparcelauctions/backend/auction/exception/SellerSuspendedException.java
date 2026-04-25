package com.slparcelauctions.backend.auction.exception;

import lombok.Getter;

/**
 * Thrown by {@code AuctionService.create} when the seller's account state
 * forbids creating a new listing — see Epic 08 sub-spec 2 §7.7 for the gate
 * order and {@link SuspensionReason} for the discriminator. Mapped to a
 * {@code 403 application/problem+json} response with the enum value carried as
 * the {@code code} property by {@link AuctionExceptionHandler}.
 */
@Getter
public class SellerSuspendedException extends RuntimeException {

    private final SuspensionReason reason;

    public SellerSuspendedException(SuspensionReason reason) {
        super("Seller is suspended from listing: " + reason);
        this.reason = reason;
    }
}
