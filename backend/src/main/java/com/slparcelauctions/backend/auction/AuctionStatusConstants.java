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
     * to ACTIVE. After the state-machine rewire (spec 2026-05-17), the set
     * consists entirely of statuses code actually transitions into — every
     * member corresponds to a live phase of the listing lifecycle (ACTIVE
     * auction, post-close escrow / transfer in flight, or disputed). All
     * terminal "why-it-ended" statuses (COMPLETED, CANCELLED, EXPIRED, FROZEN)
     * release the lock so the parcel can be re-listed. See spec §8.3.
     */
    public static final Set<AuctionStatus> LOCKING_STATUSES = Set.of(
            AuctionStatus.ACTIVE,
            AuctionStatus.TRANSFER_PENDING,
            AuctionStatus.DISPUTED);

    private AuctionStatusConstants() {
        throw new UnsupportedOperationException();
    }
}
