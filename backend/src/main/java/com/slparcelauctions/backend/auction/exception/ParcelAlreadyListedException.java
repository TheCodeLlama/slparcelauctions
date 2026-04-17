package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code AuctionVerificationService} when a verify request would
 * transition an auction to ACTIVE but another auction on the same parcel is
 * already in one of the six
 * {@link com.slparcelauctions.backend.auction.AuctionStatusConstants#LOCKING_STATUSES
 * locking statuses}. Maps to HTTP 409 with {@code code=PARCEL_ALREADY_LISTED},
 * {@code parcelId}, and {@code blockingAuctionId}.
 *
 * <p>There are two sources: the service-layer pre-check
 * ({@code assertParcelNotLocked}) which looks up the blocking auction so the
 * response identifies it, and the Postgres partial unique index backstop which
 * catches true concurrent races and surfaces as {@code blockingAuctionId=-1}
 * because the winning transaction's ID is not available at catch-time.
 */
public class ParcelAlreadyListedException extends RuntimeException {

    private final Long parcelId;
    private final Long blockingAuctionId;

    public ParcelAlreadyListedException(Long parcelId, Long blockingAuctionId) {
        super("Parcel " + parcelId + " is already listed in auction " + blockingAuctionId);
        this.parcelId = parcelId;
        this.blockingAuctionId = blockingAuctionId;
    }

    public Long getParcelId() {
        return parcelId;
    }

    public Long getBlockingAuctionId() {
        return blockingAuctionId;
    }
}
