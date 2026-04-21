package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * A bidder's standing instruction to automatically out-bid competitors up to
 * {@code maxAmount}. The proxy engine materialises a {@link Bid} with
 * {@link BidType#PROXY_AUTO} whenever it fires.
 *
 * <p>A single bidder may only have one {@link ProxyBidStatus#ACTIVE} proxy per
 * auction at a time. That invariant is enforced at the database layer via the
 * partial unique index {@code proxy_bids_one_active_per_user}, created by
 * {@link com.slparcelauctions.backend.auction.config.ProxyBidPartialUniqueIndexInitializer}. Non-active rows
 * ({@code EXHAUSTED}, {@code CANCELLED}) are preserved for audit so retiring a
 * proxy and creating a new one still round-trips cleanly.
 */
@Entity
@Table(name = "proxy_bids",
        indexes = {
            @Index(name = "ix_proxy_bids_auction_status", columnList = "auction_id, status"),
            @Index(name = "ix_proxy_bids_user_auction", columnList = "user_id, auction_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProxyBid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User bidder;

    @Column(name = "max_amount", nullable = false)
    private Long maxAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProxyBidStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
