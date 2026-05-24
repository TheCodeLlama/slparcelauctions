package com.slparcelauctions.backend.auction.exception;

import java.util.UUID;

/**
 * Thrown by {@code AuctionController.parcelScan} when the auction has no
 * scan rows (either layout or heightmap is absent, or the auction itself
 * is unknown). Maps to 404 / {@code PARCEL_SCAN_NOT_FOUND} via
 * {@link AuctionExceptionHandler}.
 */
public class AuctionParcelScanNotFoundException extends RuntimeException {

    public AuctionParcelScanNotFoundException(UUID publicId) {
        super("parcel scan not found for auction " + publicId);
    }
}
