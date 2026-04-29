package com.slparcelauctions.backend.admin.ban;

import java.time.OffsetDateTime;
import java.util.UUID;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents an admin-issued ban record. A ban may target an IP address,
 * an SL avatar UUID, or both (see {@link BanType}). The active-ban predicate
 * is: {@code liftedAt IS NULL AND (expiresAt IS NULL OR expiresAt > now)}.
 *
 * <p>Validation that a given {@link BanType} has the required identifier
 * fields populated is enforced at the service level in Task 9 — not via
 * a {@code @Check} DB constraint (avoids Postgres INET-type complexity and
 * keeps the constraint readable in Java).
 *
 * <p>Indexes:
 * <ul>
 *   <li>ip_address — fast lookup for {@code findActiveByIp}</li>
 *   <li>sl_avatar_uuid — fast lookup for {@code findActiveByAvatar}</li>
 *   <li>(lifted_at, expires_at) — supports the active-ban temporal predicate
 *       in both list and check queries</li>
 * </ul>
 */
@Entity
@Table(
    name = "bans",
    indexes = {
        @Index(name = "idx_bans_ip_address", columnList = "ip_address"),
        @Index(name = "idx_bans_sl_avatar_uuid", columnList = "sl_avatar_uuid"),
        @Index(name = "idx_bans_lifted_expires", columnList = "lifted_at, expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ban {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Who issued the ban. Lazy-loaded — only needed for admin-detail views.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_user_id", nullable = false)
    private User adminUser;

    /**
     * Which identifier(s) the ban applies to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ban_type", nullable = false, length = 10)
    private BanType banType;

    /**
     * IPv4 or IPv6 address (max 45 chars). Populated when
     * {@code banType = IP} or {@code banType = BOTH}.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Second Life avatar UUID. Populated when
     * {@code banType = AVATAR} or {@code banType = BOTH}.
     */
    @Column(name = "sl_avatar_uuid")
    private UUID slAvatarUuid;

    /**
     * Structured reason category for admin reporting.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false, length = 20)
    private BanReasonCategory reasonCategory;

    /**
     * Free-text narrative from the admin. Optional.
     */
    @Column(columnDefinition = "text")
    private String notes;

    /**
     * When the ban expires. {@code null} = permanent.
     */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /**
     * Set by an admin to lift the ban early. {@code null} = still active
     * (assuming not expired). Non-null = lifted (inactive regardless of
     * {@code expiresAt}).
     */
    @Column(name = "lifted_at")
    private OffsetDateTime liftedAt;

    /**
     * Who lifted the ban. Only meaningful when {@code liftedAt} is non-null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lifted_by_user_id")
    private User liftedByUser;

    /**
     * Admin-provided reason for lifting the ban early.
     * Only meaningful when {@code liftedAt} is non-null.
     */
    @Column(name = "lifted_reason", columnDefinition = "text")
    private String liftedReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
