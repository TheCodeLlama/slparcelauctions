package com.slparcelauctions.backend.auction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parceltag.ParcelTag;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
@Builder
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parcel_id", nullable = false)
    private Parcel parcel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_agent_id")
    private User listingAgent;

    @Column(name = "realty_group_id")
    private Long realtyGroupId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuctionStatus status = AuctionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_tier", length = 10)
    private VerificationTier verificationTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", length = 20)
    private VerificationMethod verificationMethod;

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;

    @Column(name = "sale_sentinel_price")
    private Long saleSentinelPrice;

    @Column(name = "last_bot_check_at")
    private OffsetDateTime lastBotCheckAt;

    @Builder.Default
    @Column(name = "bot_check_failures", nullable = false)
    private Integer botCheckFailures = 0;

    @Column(name = "last_ownership_check_at")
    private OffsetDateTime lastOwnershipCheckAt;

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
    @Column(name = "agent_fee_rate", precision = 5, scale = 4)
    private BigDecimal agentFeeRate = new BigDecimal("0.0000");

    @Column(name = "agent_fee_amt")
    private Long agentFeeAmt;

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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
