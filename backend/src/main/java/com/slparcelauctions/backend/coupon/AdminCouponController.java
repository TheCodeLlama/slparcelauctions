package com.slparcelauctions.backend.coupon;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.coupon.dto.CouponDto;
import com.slparcelauctions.backend.coupon.dto.CouponSummaryDto;
import com.slparcelauctions.backend.coupon.dto.CreateCouponRequest;
import com.slparcelauctions.backend.coupon.dto.PatchCouponRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin CRUD for coupon templates. Every endpoint is gated by
 * {@code hasRole('ADMIN')} so a non-admin token short-circuits at 403
 * before any service code runs; the matching {@code /api/v1/admin/**}
 * matcher in {@code SecurityConfig} is redundant but kept for defence in
 * depth.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/coupons} - paged list with optional
 *       {@code q} (code substring), {@code active} flag, and
 *       {@code discount_target} filters. Each row carries pre-computed
 *       grant counters ({@code totalGrants} unconditional;
 *       {@code activeGrants} only ACTIVE).</li>
 *   <li>{@code GET /api/v1/admin/coupons/{publicId}} - full detail with
 *       allowlist publicIds. 404 maps to
 *       {@link CouponRedemptionError#UNKNOWN_CODE}.</li>
 *   <li>{@code POST /api/v1/admin/coupons} - create. The creating
 *       admin's id is recorded on {@code coupons.created_by_user_id}.
 *       Validation rejections (lifetime / signup-window pair /
 *       duplicate code) return 409 + {@link CouponException}.</li>
 *   <li>{@code PATCH /api/v1/admin/coupons/{publicId}} - partial update
 *       with IMMUTABLE_FIELD guards on the lifetime axes once any
 *       grant exists.</li>
 *   <li>{@code DELETE /api/v1/admin/coupons/{publicId}} - hard-delete
 *       when zero grants exist, soft-archive (active=false +
 *       redeemableUntil=now) otherwise.</li>
 * </ul>
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-05-20-coupon-codes-plan.md}
 * Task 13.
 */
@RestController
@RequestMapping("/api/v1/admin/coupons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final CouponService service;
    private final CouponGrantRepository grantRepo;
    private final CouponMapper mapper;

    // @Transactional on every controller method that maps a Coupon
    // outside the service: the mapper iterates the lazy
    // {@code discounts} / {@code allowedUsers} collections, which would
    // otherwise throw LazyInitializationException now that OSIV is off.
    // The inner service call uses propagation REQUIRED and joins this
    // outer tx, so the session stays open through the mapper.

    @GetMapping
    @Transactional(readOnly = true)
    public PagedResponse<CouponSummaryDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(name = "discount_target", required = false) DiscountTarget target,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        Page<Coupon> p = service.listAdmin(q, active, target, PageRequest.of(page, size));
        return PagedResponse.from(p.map(c -> mapper.toSummary(
                c,
                grantRepo.countByCouponId(c.getId()),
                grantRepo.countByCouponIdAndStateActive(c.getId()))));
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public CouponDto get(@PathVariable UUID publicId) {
        return mapper.toDto(service.findByPublicId(publicId));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CouponDto> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateCouponRequest req) {
        Coupon c = service.createCoupon(req, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(c));
    }

    @PatchMapping("/{publicId}")
    @Transactional
    public CouponDto patch(
            @PathVariable UUID publicId,
            @Valid @RequestBody PatchCouponRequest req) {
        return mapper.toDto(service.patch(publicId, req));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<Void> archive(@PathVariable UUID publicId) {
        service.archive(publicId);
        return ResponseEntity.noContent().build();
    }
}
