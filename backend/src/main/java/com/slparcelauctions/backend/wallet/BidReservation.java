package com.slparcelauctions.backend.wallet;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Active bid reservation for the wallet model's hard-reservation flow.
 *
 * <p>When user A becomes high bidder on auction X, a row is inserted with
 * {@code releasedAt=null}. When outbid, {@code releasedAt} is set to
 * {@code now} and {@code releaseReason} stamped. Auction close consumes
 * the winning row by setting {@code releaseReason=ESCROW_FUNDED}.
 *
 * <p>The partial unique index on {@code (user_id, auction_id) WHERE
 * released_at IS NULL} enforces "at most one active reservation per
 * (user, auction)". Outbid → release prior, insert new works because
 * the prior row's {@code releasedAt} is no longer NULL when the next
 * insert happens.
 *
 * <p>See spec docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.3.
 */
@Entity
@Table(name = "bid_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "bid_id", nullable = false)
    private Long bidId;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * NULL while the reservation is active. Set to {@code now} at the
     * moment the reservation is consumed/released.
     */
    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    /**
     * NULL while active; required when {@code releasedAt} is set
     * (consistency check enforced at the DB level).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 32)
    private BidReservationReleaseReason releaseReason;

    public boolean isActive() {
        return releasedAt == null;
    }
}
