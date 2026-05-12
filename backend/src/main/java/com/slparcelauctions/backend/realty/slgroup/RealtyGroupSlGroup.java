package com.slparcelauctions.backend.realty.slgroup;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A realty group's claim on an SL group it manages land for. Created in a pending state
 * (verified=false, verification_code populated); flipped to verified by one of two paths
 * (about-text polling or founder-terminal callback). UNIQUE(sl_group_uuid) ensures an SL
 * group is registered to at most one realty group at any time across the whole system.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md}
 * §3.1, §7.
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

    @Column(name = "last_polled_at")
    private OffsetDateTime lastPolledAt;

    @Column(name = "poll_attempts", nullable = false)
    private int pollAttempts;

    @Column(name = "founder_avatar_uuid")
    private UUID founderAvatarUuid;
}
