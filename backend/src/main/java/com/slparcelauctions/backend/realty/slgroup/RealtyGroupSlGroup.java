package com.slparcelauctions.backend.realty.slgroup;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A realty group's claim on an SL group it manages land for. Created in a pending state
 * (verified=false, verification_code populated); flipped to verified by the founder-terminal
 * callback (the about-text path is removed in sub-project F). UNIQUE(sl_group_uuid) ensures
 * an SL group is registered to at most one realty group at any time across the whole system.
 *
 * <p>Sub-project F V28 introduced drift + unregister tracking (founder-uuid revalidation,
 * fetch-failure counters, drift detection + acknowledgement, admin force-unregister audit).
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md}
 * §3.1, §7 and {@code docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md}
 * §4.4.
 */
@Entity
@Table(name = "realty_group_sl_groups",
        indexes = {
            @Index(name = "ix_rg_sl_groups_realty_group", columnList = "realty_group_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupSlGroup extends BaseMutableEntity {

    @Column(name = "realty_group_id", nullable = false)
    private Long realtyGroupId;

    @Column(name = "sl_group_uuid", nullable = false)
    private UUID slGroupUuid;

    @Column(name = "sl_group_name")
    private String slGroupName;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verified_via", length = 20)
    private SlGroupVerifyMethod verifiedVia;

    @Column(name = "verification_code", length = 32)
    private String verificationCode;

    @Column(name = "verification_code_expires_at")
    private OffsetDateTime verificationCodeExpiresAt;

    @Column(name = "founder_avatar_uuid")
    private UUID founderAvatarUuid;

    // ------------------------------------------------------------------------
    // Sub-project F — drift + unregister tracking (V28)
    // ------------------------------------------------------------------------

    /**
     * Stamp of the most recent successful re-validation against the SL World API. NULL until
     * the first re-validation runs. Drives the periodic re-verify cadence.
     */
    @Column(name = "last_revalidated_at")
    private OffsetDateTime lastRevalidatedAt;

    /**
     * Most recently observed founder UUID for the SL group. Compared against
     * {@link #founderAvatarUuid} to detect founder drift.
     */
    @Column(name = "current_founder_uuid")
    private UUID currentFounderUuid;

    /** Number of consecutive World API fetch failures since the last success. */
    @Column(name = "consecutive_fetch_failures", nullable = false)
    private int consecutiveFetchFailures;

    /** Stamp the re-verify task first noticed the SL group had drifted from its claimed state. */
    @Column(name = "drift_detected_at")
    private OffsetDateTime driftDetectedAt;

    /** Coded reason for the observed drift (e.g. {@code FOUNDER_CHANGED}). */
    @Column(name = "drift_reason", length = 64)
    private String driftReason;

    /** Stamp an admin acknowledged the drift from the moderation surface. */
    @Column(name = "drift_acknowledged_at")
    private OffsetDateTime driftAcknowledgedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drift_acknowledged_by_admin_id")
    private User driftAcknowledgedByAdmin;

    /** Stamp an admin force-unregistered the row from the moderation surface. */
    @Column(name = "unregistered_at")
    private OffsetDateTime unregisteredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unregistered_by_admin_id")
    private User unregisteredByAdmin;

    /** Coded reason for the unregister action. */
    @Column(name = "unregister_reason", length = 64)
    private String unregisterReason;
}
