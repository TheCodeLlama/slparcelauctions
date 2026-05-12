package com.slparcelauctions.backend.realty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Per-(group, user) membership row.
 *
 * <p>Free-form multi-group: a single user can hold many membership rows across many groups
 * ({@code UNIQUE (group_id, user_id)}, not {@code UNIQUE (user_id)}). One row per (group,
 * user) pair.
 *
 * <p>Role is computed at read time: a member whose {@code userId == group.leaderId} is the
 * leader; every other member is an agent. The leader's row exists for query convenience
 * ("list all members") but its {@link #permissions} field is ignored by the authorizer —
 * the leader holds every permission implicitly.
 */
@Entity
@Table(
    name = "realty_group_members",
    uniqueConstraints = @UniqueConstraint(name = "realty_group_members_group_id_user_id_key",
        columnNames = {"group_id", "user_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupMember extends BaseMutableEntity {

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Postgres native {@code TEXT[]} storing {@link RealtyGroupPermission} names. Empty
     * array (not null) is the default. Use {@link #permissionSet()} / {@link
     * #setPermissionSet(Set)} for the typed view.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "permissions", columnDefinition = "text[]", nullable = false)
    private String[] permissions = new String[0];

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    /**
     * Per-listing agent commission rate snapshotted onto auctions at create-time (case 3).
     * Stored as a fraction ({@code 0.10} = 10%). Leader-edited via the invitation +
     * edit-permissions surface; non-leader members see their own rate read-only. Has no
     * effect on case-1 legacy auctions, which still use the snapshot from the group-level
     * rate/split fields until G removes them.
     */
    @Builder.Default
    @Column(name = "agent_commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal agentCommissionRate = BigDecimal.ZERO;

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
                // Forward-compat: ignore unknown permission strings in case a downstream
                // sub-project rolls back an enum value.
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

    @Transient
    public boolean hasPermission(RealtyGroupPermission p) {
        if (permissions == null) return false;
        String name = p.name();
        for (String s : permissions) {
            if (name.equals(s)) return true;
        }
        return false;
    }

    @Transient
    public boolean permissionsEqual(Set<RealtyGroupPermission> other) {
        return permissionSet().equals(other);
    }

    /** Convenience for tests / debugging. */
    @Transient
    public String[] permissionsCopy() {
        return permissions == null ? new String[0] : Arrays.copyOf(permissions, permissions.length);
    }
}
