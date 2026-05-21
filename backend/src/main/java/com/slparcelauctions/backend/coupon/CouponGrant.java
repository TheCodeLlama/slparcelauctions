package com.slparcelauctions.backend.coupon;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Per-user instance of a {@link Coupon}. Created by redemption, admin
 * direct-grant, or signup-window auto-grant. Consumed at listing
 * activation (DRAFT_PAID to ACTIVE) by decrementing
 * {@code remainingCount}; the hourly sweeper transitions ACTIVE grants
 * past {@code expiresAt} to {@link CouponGrantState#EXPIRED}.
 */
@Entity
@Table(name = "coupon_grants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CouponGrant extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    @JsonIgnore
    private Coupon coupon;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @CreationTimestamp
    @Column(name = "granted_at", nullable = false, updatable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "remaining_count")
    private Integer remainingCount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CouponGrantState state = CouponGrantState.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CouponGrantSource source;
}
