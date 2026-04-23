package com.slparcelauctions.backend.auction.exception;

import lombok.Getter;

/**
 * Raised when an auction has reached the configured maximum number of photos
 * ({@code slpa.photos.max-per-listing}, default 10) and the seller attempts to
 * upload another. Mapped to HTTP 413 (Payload Too Large) by
 * {@link AuctionExceptionHandler}.
 */
@Getter
public class PhotoLimitExceededException extends RuntimeException {

    private final int currentCount;
    private final int maxAllowed;

    public PhotoLimitExceededException(int currentCount, int maxAllowed) {
        super("Photo limit exceeded: " + currentCount + " / " + maxAllowed);
        this.currentCount = currentCount;
        this.maxAllowed = maxAllowed;
    }
}
