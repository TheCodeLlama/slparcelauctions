package com.slparcelauctions.backend.coupon;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Coupon template. One row per admin-created code; per-user instances
 * live on {@link CouponGrant}. The discount payload is a 1:N collection
 * of {@link CouponDiscount} so a single coupon can carry multiple
 * discount lines that all apply together.
 *
 * <p>Lifetime axes: at least one of {@code durationDays} or
 * {@code useCount} must be set (enforced by DB CHECK). Both null would
 * make every grant infinite which the spec explicitly disallows.
 *
 * <p>Signup window: either both of {@code signupWindowStart} and
 * {@code signupWindowEnd} are set, or neither (enforced by DB CHECK).
 * When both are set, new users created within that window get an
 * automatic grant; coupon creation also backfills matching
 * pre-existing users.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-20-coupon-codes-design.md}.
 */
@Entity
@Table(name = "coupons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Coupon extends BaseMutableEntity {

    @Column(nullable = false, length = 64, unique = true)
    private String code;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "use_count")
    private Integer useCount;

    @Column(name = "redeemable_until")
    private OffsetDateTime redeemableUntil;

    @Column(name = "max_total_redemptions")
    private Integer maxTotalRedemptions;

    @Builder.Default
    @Column(name = "max_per_user", nullable = false)
    private Integer maxPerUser = 1;

    @Column(name = "signup_window_start")
    private LocalDate signupWindowStart;

    @Column(name = "signup_window_end")
    private LocalDate signupWindowEnd;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Builder.Default
    @Column(name = "notify_on_grant", nullable = false)
    private Boolean notifyOnGrant = true;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Builder.Default
    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CouponDiscount> discounts = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CouponGrant> grants = new ArrayList<>();

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "coupon_allowed_users",
            joinColumns = @JoinColumn(name = "coupon_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> allowedUsers = new HashSet<>();
}
