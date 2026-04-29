package com.slparcelauctions.backend.auction.saved.exception;

import lombok.Getter;

/**
 * Raised when a user tries to save an auction whose status is one of the
 * pre-active states (DRAFT, DRAFT_PAID, VERIFICATION_PENDING,
 * VERIFICATION_FAILED). Maps to HTTP 403 with code
 * {@code CANNOT_SAVE_PRE_ACTIVE}.
 */
@Getter
public class CannotSavePreActiveException extends RuntimeException {

    private final Long auctionId;
    private final String currentStatus;

    public CannotSavePreActiveException(Long auctionId, String currentStatus) {
        super("Cannot save auction in pre-active status: " + currentStatus);
        this.auctionId = auctionId;
        this.currentStatus = currentStatus;
    }
}
