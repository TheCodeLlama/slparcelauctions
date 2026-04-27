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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "cancellation_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(name = "cancelled_from_status", nullable = false, length = 30)
    private String cancelledFromStatus;

    @Builder.Default
    @Column(name = "had_bids", nullable = false)
    private Boolean hadBids = false;

    @Column(length = 500)
    private String reason;

    /**
     * Snapshot of the ladder consequence selected at the moment of this
     * cancellation. Immutable historical fact — never recomputed. Nullable
     * because pre-sub-spec-2 rows have no kind. See spec §4.3.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_kind", length = 30)
    private CancellationOffenseKind penaltyKind;

    /**
     * Snapshot of the L$ debt added by this cancellation. Populated when
     * {@code penaltyKind} is {@code PENALTY} or {@code PENALTY_AND_30D};
     * {@code null} for {@code NONE}, {@code WARNING}, and
     * {@code PERMANENT_BAN}. Pre-sub-spec-2 rows are also {@code null}.
     */
    @Column(name = "penalty_amount_l")
    private Long penaltyAmountL;

    /**
     * Non-null when the cancellation was initiated by a staff admin rather than
     * the seller. Admin-cancel rows are excluded from
     * {@code countPriorOffensesWithBids} so they do not advance the seller's
     * penalty ladder. Null for all seller-initiated cancellations.
     */
    @Column(name = "cancelled_by_admin_id")
    private Long cancelledByAdminId;

    @CreationTimestamp
    @Column(name = "cancelled_at", nullable = false, updatable = false)
    private OffsetDateTime cancelledAt;
}
