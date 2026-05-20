package com.slparcelauctions.backend.coupon;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.coupon.dto.CouponDiscountDto;
import com.slparcelauctions.backend.coupon.dto.CreateCouponRequest;
import com.slparcelauctions.backend.coupon.dto.PatchCouponRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Coupon admin CRUD + (in later tasks) redemption / direct-grant /
 * signup-window backfill / revoke. This file currently holds the admin
 * CRUD core only:
 *
 * <ul>
 *   <li>{@link #createCoupon} - persist template + discount lines +
 *       allowlist M2M</li>
 *   <li>{@link #findByPublicId} - throws UNKNOWN_CODE if absent</li>
 *   <li>{@link #listAdmin} - paged Specification search by code/active/target</li>
 *   <li>{@link #patch} - partial update; IMMUTABLE_FIELD guards
 *       {@code durationDays} / {@code useCount} / {@code maxPerUser}
 *       once any grant exists</li>
 *   <li>{@link #archive} - hard delete when 0 grants, soft archive
 *       (active=false + redeemableUntil=now) otherwise</li>
 * </ul>
 *
 * <p>Lifetime rule: at least one of {@code durationDays} or
 * {@code useCount} must be set (LIFETIME_REQUIRED). Signup-window rule:
 * both {@code signupWindowStart} and {@code signupWindowEnd} must be
 * set together or both null (SIGNUP_WINDOW_PAIRED).
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-05-20-coupon-codes-plan.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CouponService {

    private final CouponRepository couponRepo;
    private final CouponGrantRepository grantRepo;
    private final UserRepository userRepo;

    public Coupon createCoupon(CreateCouponRequest req, long adminUserId) {
        validateLifetime(req.durationDays(), req.useCount());
        validateSignupWindowPaired(req.signupWindowStart(), req.signupWindowEnd());
        if (couponRepo.findByCodeIgnoreCase(req.code()).isPresent()) {
            throw new CouponException(
                    CouponRedemptionError.IMMUTABLE_FIELD, "code already exists");
        }
        Coupon c = Coupon.builder()
                .code(req.code())
                .description(req.description())
                .durationDays(req.durationDays())
                .useCount(req.useCount())
                .redeemableUntil(req.redeemableUntil())
                .maxTotalRedemptions(req.maxTotalRedemptions())
                .maxPerUser(req.maxPerUser() != null ? req.maxPerUser() : 1)
                .signupWindowStart(req.signupWindowStart())
                .signupWindowEnd(req.signupWindowEnd())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .notifyOnGrant(req.notifyOnGrant() == null ? Boolean.TRUE : req.notifyOnGrant())
                .createdByUserId(adminUserId)
                .build();
        int order = 0;
        List<CouponDiscount> discounts = new ArrayList<>();
        for (CouponDiscountDto d : req.discounts()) {
            discounts.add(CouponDiscount.builder()
                    .coupon(c)
                    .target(d.target())
                    .op(d.op())
                    .value(d.value())
                    .sortOrder(d.sortOrder() != null ? d.sortOrder() : order)
                    .build());
            order++;
        }
        c.setDiscounts(discounts);
        if (req.allowedUserPublicIds() != null && !req.allowedUserPublicIds().isEmpty()) {
            c.setAllowedUsers(resolveAllowedUsers(req.allowedUserPublicIds()));
        }
        return couponRepo.save(c);
    }

    @Transactional(readOnly = true)
    public Coupon findByPublicId(UUID publicId) {
        return couponRepo.findByPublicId(publicId)
                .orElseThrow(() -> new CouponException(CouponRedemptionError.UNKNOWN_CODE));
    }

    @Transactional(readOnly = true)
    public Page<Coupon> listAdmin(String q, Boolean active, DiscountTarget target, Pageable pageable) {
        Specification<Coupon> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("code")),
                        "%" + q.toLowerCase() + "%"));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (target != null) {
                Join<Coupon, CouponDiscount> j = root.join("discounts");
                cq.distinct(true);
                predicates.add(cb.equal(j.get("target"), target));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return couponRepo.findAll(spec, pageable);
    }

    public Coupon patch(UUID publicId, PatchCouponRequest req) {
        Coupon c = findByPublicId(publicId);
        long totalGrants = grantRepo.countByCouponId(c.getId());
        if (totalGrants > 0) {
            if (req.durationDays() != null) {
                throw new CouponException(
                        CouponRedemptionError.IMMUTABLE_FIELD, "durationDays");
            }
            if (req.useCount() != null) {
                throw new CouponException(
                        CouponRedemptionError.IMMUTABLE_FIELD, "useCount");
            }
            if (req.maxPerUser() != null) {
                throw new CouponException(
                        CouponRedemptionError.IMMUTABLE_FIELD, "maxPerUser");
            }
        } else {
            if (req.durationDays() != null) c.setDurationDays(req.durationDays());
            if (req.useCount() != null) c.setUseCount(req.useCount());
            if (req.maxPerUser() != null) c.setMaxPerUser(req.maxPerUser());
        }
        if (req.description() != null) c.setDescription(req.description());
        if (req.active() != null) c.setActive(req.active());
        if (req.notifyOnGrant() != null) c.setNotifyOnGrant(req.notifyOnGrant());
        if (req.redeemableUntil() != null) c.setRedeemableUntil(req.redeemableUntil());
        if (req.maxTotalRedemptions() != null) c.setMaxTotalRedemptions(req.maxTotalRedemptions());
        if (req.allowedUserPublicIds() != null) {
            c.setAllowedUsers(resolveAllowedUsers(req.allowedUserPublicIds()));
        }
        return c;
    }

    public void archive(UUID publicId) {
        Coupon c = findByPublicId(publicId);
        long totalGrants = grantRepo.countByCouponId(c.getId());
        if (totalGrants > 0) {
            c.setActive(false);
            c.setRedeemableUntil(OffsetDateTime.now());
        } else {
            couponRepo.delete(c);
        }
    }

    private Set<User> resolveAllowedUsers(List<UUID> publicIds) {
        Set<User> users = new LinkedHashSet<>();
        for (UUID pid : publicIds) {
            users.add(userRepo.findByPublicId(pid).orElseThrow(
                    () -> new CouponException(
                            CouponRedemptionError.UNKNOWN_CODE, "unknown user " + pid)));
        }
        return users;
    }

    private void validateLifetime(Integer durationDays, Integer useCount) {
        if (durationDays == null && useCount == null) {
            throw new CouponException(CouponRedemptionError.LIFETIME_REQUIRED);
        }
    }

    private void validateSignupWindowPaired(LocalDate start, LocalDate end) {
        if ((start == null) != (end == null)) {
            throw new CouponException(CouponRedemptionError.SIGNUP_WINDOW_PAIRED);
        }
    }
}
