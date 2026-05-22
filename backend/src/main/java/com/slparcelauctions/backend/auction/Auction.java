package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "auctions",
        indexes = {
            @Index(name = "ix_auctions_status_ends_at", columnList = "status, ends_at"),
            @Index(name = "ix_auctions_status_starts_at", columnList = "status, starts_at DESC"),
            @Index(name = "ix_auctions_status_current_bid", columnList = "status, current_bid"),
            @Index(name = "ix_auctions_seller_status", columnList = "seller_id, status"),
            @Index(name = "ix_auctions_status_reserve", columnList = "status, reserve_price")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Auction extends BaseMutableEntity {

    @Column(name = "sl_parcel_uuid", nullable = false)
    private UUID slParcelUuid;

    @Setter(AccessLevel.NONE)
    @OneToOne(mappedBy = "auction", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private AuctionParcelSnapshot parcelSnapshot;

    /**
     * Setter override that keeps the denormalized {@link #slParcelUuid}
     * mirror in sync with the snapshot's UUID. The mirror exists because the
     * parcel-locking partial unique index lives on the auctions table —
     * Postgres partial indexes can't span tables.
     */
    public void setParcelSnapshot(AuctionParcelSnapshot snapshot) {
        this.parcelSnapshot = snapshot;
        if (snapshot != null) {
            snapshot.setAuction(this);
            this.slParcelUuid = snapshot.getSlParcelUuid();
        }
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_agent_id")
    private User listingAgent;

    @Column(name = "realty_group_id")
    private Long realtyGroupId;

    /**
     * Group-sale discriminator. NULL for individual listings and for legacy non-SL-group
     * realty-group rows. Set at create-time to the verified {@link com.slparcelauctions
     * .backend.realty.slgroup.RealtyGroupSlGroup} row whose SL group UUID matches the
     * parcel's SL owner.
     */
    @Column(name = "realty_group_sl_group_id")
    private Long realtyGroupSlGroupId;

    /**
     * Per-listing commission rate snapshotted from {@code realty_group_members.agent_commission_rate}
     * at listing-create. NULL for individual sales. Consumed by {@code AgentCommissionDistributor}
     * at SOLD close.
     */
    @Column(name = "agent_commission_rate", precision = 5, scale = 4)
    private java.math.BigDecimal agentCommissionRate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuctionStatus status = AuctionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_tier", length = 10)
    private VerificationTier verificationTier;

    @Column(name = "last_ownership_check_at")
    private OffsetDateTime lastOwnershipCheckAt;

    /**
     * Count of consecutive World API ownership checks where the parcel
     * owner did not match the expected seller / SL group. Reset to 0 on
     * any match. The ACTIVE-state OwnershipCheckTask suspends only once
     * this counter crosses
     * {@code slpa.ownership-monitor.mismatch-streak-threshold} (default
     * 2) so a single transient World API result cannot tip a live
     * listing into SUSPENDED. The post-cancel watcher path is
     * intentionally not gated by this streak -- it is a one-shot
     * forensic probe and flags on the first observed mismatch.
     */
    @Builder.Default
    @Column(name = "consecutive_owner_mismatches", nullable = false)
    private Integer consecutiveOwnerMismatches = 0;

    @Column(name = "suspended_at")
    private OffsetDateTime suspendedAt;

    /**
     * Watch-window deadline for the post-cancellation ownership probe (Epic 08
     * sub-spec 2 §6). Set when an {@code ACTIVE}-with-bids auction is
     * cancelled, to {@code now + slpa.cancellation.post-cancel-watch-hours}
     * (default 48h). The ownership monitor scans for cancelled auctions whose
     * watch window is still open and raises a {@code CANCEL_AND_SELL} fraud
     * flag if the parcel ownership flips to a non-seller avatar within the
     * window. {@code null} for cancellations that don't qualify (pre-active,
     * or active-without-bids).
     */
    @Column(name = "post_cancel_watch_until")
    private OffsetDateTime postCancelWatchUntil;

    @Builder.Default
    @Column(name = "consecutive_world_api_failures", nullable = false, columnDefinition = "integer NOT NULL DEFAULT 0")
    private Integer consecutiveWorldApiFailures = 0;

    @Builder.Default
    @Column(name = "listing_fee_paid", nullable = false)
    private Boolean listingFeePaid = false;

    @Column(name = "listing_fee_amt")
    private Long listingFeeAmt;

    @Column(name = "listing_fee_txn", length = 255)
    private String listingFeeTxn;

    @Column(name = "listing_fee_paid_at")
    private OffsetDateTime listingFeePaidAt;

    @Column(name = "listing_fee_coupon_grant_id")
    private Long listingFeeCouponGrantId;

    @Column(name = "commission_coupon_grant_id")
    private Long commissionCouponGrantId;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verification_notes", columnDefinition = "text")
    private String verificationNotes;

    /**
     * Seller-written headline for the listing (1–120 chars, NOT NULL). Distinct
     * from the SL parcel name, which is often low-signal ("Object", "Gov Linden's
     * 1024"). Required at create via {@code AuctionCreateRequest#title};
     * production writes always go through {@code AuctionService.create}, which
     * sets the title from the validated request DTO.
     */
    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "starting_bid", nullable = false)
    private Long startingBid;

    @Builder.Default
    @Column(name = "bid_increment", nullable = false)
    private Long bidIncrement = 50L;

    @Column(name = "reserve_price")
    private Long reservePrice;

    @Column(name = "buy_now_price")
    private Long buyNowPrice;

    @Builder.Default
    @Column(name = "current_bid", nullable = false)
    private Long currentBid = 0L;

    @Builder.Default
    @Column(name = "bid_count", nullable = false)
    private Integer bidCount = 0;

    @Builder.Default
    @Column(name = "is_featured", nullable = false)
    private boolean isFeatured = false;

    @Column(name = "featured_until")
    private OffsetDateTime featuredUntil;

    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "current_bidder_id")
    private Long currentBidderId;

    @Column(name = "winner_user_id")
    private Long winnerUserId;

    @Column(name = "final_bid_amount")
    private Long finalBidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_outcome", length = 32)
    private AuctionEndOutcome endOutcome;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours;

    @Builder.Default
    @Column(name = "snipe_protect", nullable = false)
    private Boolean snipeProtect = false;

    @Column(name = "snipe_window_min")
    private Integer snipeWindowMin;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "original_ends_at")
    private OffsetDateTime originalEndsAt;

    @Column(name = "seller_desc", columnDefinition = "text")
    private String sellerDesc;

    @Builder.Default
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate = new BigDecimal("0.0500");

    @Column(name = "commission_amt")
    private Long commissionAmt;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "auction_tags",
            joinColumns = @JoinColumn(name = "auction_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"),
            indexes = @Index(name = "ix_auction_tags_tag_id", columnList = "tag_id"))
    private Set<ParcelTag> tags = new HashSet<>();

    /**
     * Read-only inverse side of {@link AuctionPhoto#getAuction()}. Hibernate
     * loads this collection lazily; the listing-detail repo method opts into
     * eager hydration via {@code @EntityGraph} so the seller card + photo
     * carousel render off a single LEFT JOIN. Writes still go through
     * {@link AuctionPhotoRepository} so this collection is intentionally not
     * cascaded — keeping the photo upload path the single source of mutation.
     */
    @Builder.Default
    @OneToMany(mappedBy = "auction", fetch = FetchType.LAZY)
    private List<AuctionPhoto> photos = new ArrayList<>();

}
