package com.slparcelauctions.backend.auction;

import java.util.Set;

/**
 * Shared constants for the auction state machine. The {@link #LOCKING_STATUSES}
 * set is the authoritative source for both service-layer checks and the
 * Postgres partial unique index DDL in {@link config.ParcelLockingIndexInitializer}.
 */
public final class AuctionStatusConstants {

    /**
     * Statuses that unconditionally block another auction on the same parcel
     * from transitioning to ACTIVE. Originally included ENDED, but a freshly-
     * ENDED auction is not a definitive parcel-lock: its escrow may have
     * reached a terminal state ({@code COMPLETED}, {@code FROZEN}, or
     * {@code EXPIRED}) — at which point the parcel is releasable — or there
     * may be no escrow at all ({@code NO_BIDS} / {@code RESERVE_NOT_MET}).
     * The ENDED-with-active-escrow case is enforced separately via
     * {@code AuctionRepository#findFirstEndedWithActiveEscrowByParcel} so it
     * can consult the escrow row. See spec §8.3.
     */
    public static final Set<AuctionStatus> LOCKING_STATUSES = Set.of(
            AuctionStatus.ACTIVE,
            AuctionStatus.ESCROW_PENDING,
            AuctionStatus.ESCROW_FUNDED,
            AuctionStatus.TRANSFER_PENDING,
            AuctionStatus.DISPUTED);

    private AuctionStatusConstants() {
        throw new UnsupportedOperationException();
    }
}
