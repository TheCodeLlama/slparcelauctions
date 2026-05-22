package com.slparcelauctions.backend.auction;

import lombok.Getter;

/**
 * Raised by {@link AuctionPhotoDarkVariantService} when a dark-variant
 * upload or delete targets a photo row whose {@link PhotoSource} is not on
 * the allow-list for dark variants. Only the auto-inserted default-cover
 * sources (sort-0 row, {@link PhotoSource#USER_DEFAULT_COVER} or
 * {@link PhotoSource#GROUP_DEFAULT_COVER}) carry a dark sibling: seller
 * uploads and SL parcel snapshots stay single-slot.
 *
 * <p>Mapped to {@code 400 INVALID_PHOTO_SOURCE} by the auction-package
 * exception handler.
 */
@Getter
public class InvalidPhotoSourceException extends RuntimeException {

    private final PhotoSource actualSource;

    public InvalidPhotoSourceException(PhotoSource actualSource) {
        super("photo source does not support dark variant: " + actualSource);
        this.actualSource = actualSource;
    }
}
