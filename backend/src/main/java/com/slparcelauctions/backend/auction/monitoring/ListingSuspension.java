package com.slparcelauctions.backend.auction.monitoring;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspension;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Audit row for one suspension event against a listing. Disambiguates the cause
 * (auto monitor, admin individual action, admin group-bulk action) and links the
 * row to the originating actor (admin, group suspension, or bulk action id).
 *
 * <p>{@code suspendedAt} is the moment the listing transitioned to {@code SUSPENDED}.
 * {@code liftedAt} non-null indicates the listing was reinstated. {@code cancelledAt}
 * non-null indicates the auto-cancel sweep cancelled the listing after a bulk-suspend
 * timer expiry. {@code liftedAt} and {@code cancelledAt} are mutually exclusive.
 *
 * <p>Sub-project F spec §4.3, §10.
 */
@Entity
@Table(name = "listing_suspensions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ListingSuspension extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ListingSuspensionCause cause;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suspended_by_admin_id")
    private User suspendedByAdmin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_suspension_id")
    private RealtyGroupSuspension groupSuspension;

    /** Group-bulk-action id; non-null only when {@link #cause} = {@code ADMIN_GROUP_BULK}. */
    @Column(name = "bulk_action_id")
    private UUID bulkActionId;

    @Column(length = 64)
    private String reason;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "suspended_at", nullable = false)
    private OffsetDateTime suspendedAt;

    @Column(name = "lifted_at")
    private OffsetDateTime liftedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;
}
