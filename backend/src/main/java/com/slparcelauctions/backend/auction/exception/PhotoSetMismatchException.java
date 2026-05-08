package com.slparcelauctions.backend.auction.exception;

import java.util.Set;
import java.util.UUID;

import lombok.Getter;

/**
 * Raised by {@code AuctionPhotoService.reorder} when the request body's
 * photo publicId set does not exactly match the auction's photo set
 * (extra UUIDs, missing UUIDs, or duplicates). Mapped to HTTP 400 with
 * {@code code=PHOTO_SET_MISMATCH}.
 */
@Getter
public class PhotoSetMismatchException extends RuntimeException {

    private final Set<UUID> expectedPublicIds;
    private final Set<UUID> actualPublicIds;

    public PhotoSetMismatchException(Set<UUID> expected, Set<UUID> actual) {
        super("Photo set mismatch: expected=" + expected + " actual=" + actual);
        this.expectedPublicIds = expected;
        this.actualPublicIds = actual;
    }
}
