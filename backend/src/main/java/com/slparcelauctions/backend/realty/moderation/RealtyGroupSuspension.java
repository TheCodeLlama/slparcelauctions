package com.slparcelauctions.backend.realty.moderation;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
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
 * Admin-issued suspension or permanent ban against a realty group.
 *
 * <p>A row with {@code expiresAt != null} is a timed suspension that auto-lifts when the
 * expiry passes (see {@code GroupSuspensionExpiryTask}). A row with {@code expiresAt == null}
 * is a permanent ban. {@code liftedAt} non-null indicates the suspension has been lifted
 * (either by admin or by the expiry sweep).
 *
 * <p>Sub-project F spec §4.1.
 */
@Entity
@Table(name = "realty_group_suspensions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupSuspension extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "realty_group_id", nullable = false)
    private RealtyGroup realtyGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_admin_id", nullable = false)
    private User issuedByAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private SuspensionReason reason;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    /** Null = permanent ban. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "lifted_at")
    private OffsetDateTime liftedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lifted_by_admin_id")
    private User liftedByAdmin;

    @Column(name = "lifted_notes", columnDefinition = "text")
    private String liftedNotes;

    /**
     * @return {@code true} if this suspension is currently in effect at {@code now}:
     *     not lifted, and either permanent ({@code expiresAt == null}) or not yet
     *     expired.
     */
    public boolean isActive(OffsetDateTime now) {
        return liftedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
