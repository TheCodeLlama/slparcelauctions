package com.slparcelauctions.backend.realty;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Invitation from a group (issued by leader or an {@code INVITE_AGENTS} delegate) to a user
 * to join the group with a specific permission set.
 *
 * <p>Lifecycle: {@code PENDING} → {@code ACCEPTED | DECLINED | REVOKED | EXPIRED}. The
 * partial unique index on {@code (group_id, invited_user_id) WHERE status = 'PENDING'}
 * prevents two live invitations for the same pair. On accept, the permissions on this row
 * are copied verbatim into the new {@link RealtyGroupMember} row.
 */
@Entity
@Table(name = "realty_group_invitations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupInvitation extends BaseMutableEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "invited_user_id", nullable = false)
    private Long invitedUserId;

    @Column(name = "invited_by_id", nullable = false)
    private Long invitedById;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "permissions", columnDefinition = "text[]", nullable = false)
    private String[] permissions = new String[0];

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Transient
    public boolean isPending() {
        return status == InvitationStatus.PENDING;
    }

    @Transient
    public boolean isExpired() {
        return status == InvitationStatus.EXPIRED
            || (status == InvitationStatus.PENDING && expiresAt != null && expiresAt.isBefore(OffsetDateTime.now()));
    }

    @Transient
    public Set<RealtyGroupPermission> permissionSet() {
        if (permissions == null || permissions.length == 0) {
            return EnumSet.noneOf(RealtyGroupPermission.class);
        }
        Set<RealtyGroupPermission> out = EnumSet.noneOf(RealtyGroupPermission.class);
        for (String name : permissions) {
            if (name == null || name.isBlank()) continue;
            try {
                out.add(RealtyGroupPermission.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // forward-compat with retired enum values
            }
        }
        return out;
    }

    public void setPermissionSet(Set<RealtyGroupPermission> perms) {
        if (perms == null || perms.isEmpty()) {
            this.permissions = new String[0];
        } else {
            this.permissions = perms.stream().map(Enum::name).toArray(String[]::new);
        }
    }
}
