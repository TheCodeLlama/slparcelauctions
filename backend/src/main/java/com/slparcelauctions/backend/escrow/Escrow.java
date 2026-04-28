package com.slparcelauctions.backend.escrow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.escrow.dispute.EvidenceImage;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction escrow lifecycle row (spec §3.1, §4). Created synchronously by
 * AuctionEndTask.closeOne on SOLD or BOUGHT_NOW outcomes. Deadlines:
 * paymentDeadline = auction.endedAt + 48h; transferDeadline = fundedAt + 72h.
 * Commission computed at creation and immutable.
 */
@Entity
@Table(name = "escrows",
        indexes = {
                @Index(name = "ix_escrows_state", columnList = "state"),
                @Index(name = "ix_escrows_payment_deadline", columnList = "payment_deadline"),
                @Index(name = "ix_escrows_transfer_deadline", columnList = "transfer_deadline")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Escrow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false, unique = true)
    private Auction auction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EscrowState state;

    @Column(name = "final_bid_amount", nullable = false)
    private Long finalBidAmount;

    @Column(name = "commission_amt", nullable = false)
    private Long commissionAmt;

    @Column(name = "payout_amt", nullable = false)
    private Long payoutAmt;

    @Column(name = "payment_deadline", nullable = false)
    private OffsetDateTime paymentDeadline;

    @Column(name = "transfer_deadline")
    private OffsetDateTime transferDeadline;

    @Column(name = "funded_at")
    private OffsetDateTime fundedAt;

    @Column(name = "transfer_confirmed_at")
    private OffsetDateTime transferConfirmedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "disputed_at")
    private OffsetDateTime disputedAt;

    @Column(name = "frozen_at")
    private OffsetDateTime frozenAt;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Builder.Default
    @Column(name = "consecutive_world_api_failures", nullable = false)
    private Integer consecutiveWorldApiFailures = 0;

    @Column(name = "dispute_reason_category", length = 40)
    private String disputeReasonCategory;

    @Column(name = "dispute_description", columnDefinition = "text")
    private String disputeDescription;

    @Column(name = "sl_transaction_key", length = 64)
    private String slTransactionKey;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "winner_evidence_images", columnDefinition = "jsonb")
    private List<EvidenceImage> winnerEvidenceImages = new ArrayList<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "seller_evidence_images", columnDefinition = "jsonb")
    private List<EvidenceImage> sellerEvidenceImages = new ArrayList<>();

    @Column(name = "seller_evidence_text", length = 2000)
    private String sellerEvidenceText;

    @Column(name = "seller_evidence_submitted_at")
    private OffsetDateTime sellerEvidenceSubmittedAt;

    @Column(name = "freeze_reason", length = 40)
    private String freezeReason;

    /**
     * Set to {@code true} by {@link EscrowService#markReviewRequired} when
     * the bot monitor observes persistent ACCESS_DENIED during escrow (spec
     * §6.2). Does not change lifecycle state; a flag for admin triage.
     * Default false.
     */
    @Builder.Default
    @Column(name = "review_required", nullable = false)
    private Boolean reviewRequired = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
