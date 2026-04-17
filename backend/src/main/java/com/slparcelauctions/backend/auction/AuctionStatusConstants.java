package com.slparcelauctions.backend.auction;

import java.util.Set;

/**
 * Shared constants for the auction state machine. The {@link #LOCKING_STATUSES}
 * set is the authoritative source for both service-layer checks and the
 * Postgres partial unique index DDL in {@link config.ParcelLockingIndexInitializer}.
 */
public final class AuctionStatusConstants {

    /**
     * Statuses that block another auction on the same parcel from transitioning
     * to ACTIVE. See spec §8.3.
     */
    public static final Set<AuctionStatus> LOCKING_STATUSES = Set.of(
            AuctionStatus.ACTIVE,
            AuctionStatus.ENDED,
            AuctionStatus.ESCROW_PENDING,
            AuctionStatus.ESCROW_FUNDED,
            AuctionStatus.TRANSFER_PENDING,
            AuctionStatus.DISPUTED);

    private AuctionStatusConstants() {
        throw new UnsupportedOperationException();
    }
}
