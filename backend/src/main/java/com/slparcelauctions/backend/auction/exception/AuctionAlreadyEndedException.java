package com.slparcelauctions.backend.auction.exception;

import java.time.OffsetDateTime;

/**
 * Raised by {@code BidService.placeBid} when {@code auction.endsAt <= now} even
 * though {@code status == ACTIVE} — the scheduler hasn't closed the row yet
 * but the clock has already passed the end time. Mapped to
 * {@code 409 AUCTION_ALREADY_ENDED} by {@code AuctionExceptionHandler}.
 *
 * <p>Distinct from {@link InvalidAuctionStateException}, which fires when the
 * auction is in a non-{@code ACTIVE} status entirely (e.g. the scheduler ran
 * first and flipped to {@code ENDED}). Both are 409s but they carry different
 * error codes so the client can render different UI copy.
 */
public class AuctionAlreadyEndedException extends RuntimeException {

    private final OffsetDateTime endsAt;

    public AuctionAlreadyEndedException(OffsetDateTime endsAt) {
        super("This auction ended at " + endsAt + ".");
        this.endsAt = endsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }
}
