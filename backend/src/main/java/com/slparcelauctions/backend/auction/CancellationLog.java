package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "cancellation_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CancellationLog extends BaseEntity {

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

    /**
     * Sub-project E §11.3 -- broker user when {@code penaltyKind = BROKER_CANCEL}.
     * Null for seller-initiated and admin-initiated cancellations. Captured so
     * audit history can reconstruct which agent acted on behalf of the group.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /**
     * Sub-project E §11.3 -- realty group context for broker cancels. Null for
     * seller-initiated and admin-initiated cancellations. Snapshotted at the
     * moment of cancel; survives later group dissolution / membership changes.
     */
    @Column(name = "realty_group_id")
    private Long realtyGroupId;

    @CreationTimestamp
    @Column(name = "cancelled_at", nullable = false, updatable = false)
    private OffsetDateTime cancelledAt;
}
