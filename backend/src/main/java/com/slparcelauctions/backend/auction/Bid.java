package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable record of a single bid placed against an auction. Rows are never
 * updated — corrections happen by emitting new rows, which keeps the bid
 * history a true append-only log for dispute resolution.
 *
 * <p>{@code proxyBidId} is a soft foreign key (plain {@code Long}, not
 * {@code @ManyToOne}) so read paths that just enumerate bids never trigger
 * eager or lazy fetches back into {@link ProxyBid}.
 */
@Entity
@Table(name = "bids",
        indexes = {
            @Index(name = "ix_bids_auction_created", columnList = "auction_id, created_at"),
            @Index(name = "ix_bids_user_auction_amount", columnList = "user_id, auction_id, amount DESC"),
            @Index(name = "ix_bids_user_created", columnList = "user_id, created_at DESC")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User bidder;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "bid_type", nullable = false, length = 16)
    private BidType bidType;

    /**
     * Soft FK to {@link ProxyBid#getId()} when this row was emitted by the
     * proxy engine. Nullable for {@link BidType#MANUAL} and
     * {@link BidType#BUY_NOW} rows. Deliberately not a {@code @ManyToOne} so
     * bid-history reads don't eager-load proxy rows.
     */
    @Column(name = "proxy_bid_id")
    private Long proxyBidId;

    /**
     * When snipe protection extended the auction as a side effect of this bid,
     * how many minutes were added. Null when no extension occurred.
     */
    @Column(name = "snipe_extension_minutes")
    private Integer snipeExtensionMinutes;

    /**
     * The {@code ends_at} value the parent auction was updated to as a result
     * of this bid's snipe extension. Null when no extension occurred.
     */
    @Column(name = "new_ends_at")
    private OffsetDateTime newEndsAt;

    /**
     * IPv4 or IPv6 source address of the request that placed this bid, for
     * abuse auditing. Sized to accommodate a full IPv6 string.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
