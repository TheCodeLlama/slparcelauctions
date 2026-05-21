package com.slparcelauctions.backend.coupon;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.coupon.dto.CouponGrantDto;
import com.slparcelauctions.backend.coupon.dto.DirectGrantRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin grant management for a single coupon template. Mirrors the auth
 * posture of {@link AdminCouponController}: every route is gated by
 * {@code hasRole('ADMIN')} so a non-admin token short-circuits at 403
 * before any service code runs.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/coupons/{publicId}/grants} - paged list
 *       of grants for the coupon, newest first by {@code grantedAt}.
 *       Optional {@code state} (ACTIVE / REVOKED / EXPIRED) and
 *       {@code source} (REDEMPTION / ADMIN_GRANT / SIGNUP_WINDOW)
 *       filters power the admin tab.</li>
 *   <li>{@code POST /api/v1/admin/coupons/{publicId}/grants} - direct
 *       grant to one or more users. Idempotent at the coupon's
 *       {@code maxPerUser} ceiling - users who already hold the maximum
 *       are skipped silently and only newly-created grants are returned
 *       in the 201 body.</li>
 *   <li>{@code POST /api/v1/admin/coupons/{publicId}/grants/{grantPublicId}/revoke}
 *       - flips the grant to {@link CouponGrantState#REVOKED}. The
 *       resolver filters revoked grants so the discount disappears
 *       immediately on the next listing.</li>
 * </ul>
 *
 * <p>404 on an unknown coupon publicId or a grant publicId that does not
 * belong to the supplied coupon: both map to
 * {@link CouponRedemptionError#UNKNOWN_CODE} via
 * {@link CouponExceptionHandler}.
 *
 * <p>Plan: {@code docs/superpowers/plans/2026-05-20-coupon-codes-plan.md}
 * Task 14.
 */
@RestController
@RequestMapping("/api/v1/admin/coupons/{publicId}/grants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponGrantController {

    private final CouponService service;
    private final CouponGrantRepository grantRepo;
    private final CouponMapper mapper;

    // @Transactional on every method: mapper::toGrantDto dereferences
    // {@code grant.coupon} (LAZY) and {@code coupon.discounts} (LAZY),
    // which throw LazyInitializationException outside a session.

    @GetMapping
    @Transactional(readOnly = true)
    public PagedResponse<CouponGrantDto> list(
            @PathVariable UUID publicId,
            @RequestParam(required = false) CouponGrantState state,
            @RequestParam(required = false) CouponGrantSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        Coupon c = service.findByPublicId(publicId);
        Page<CouponGrant> p = grantRepo.findByCouponIdAndOptionalFilters(
                c.getId(), state, source, PageRequest.of(page, size));
        return PagedResponse.from(p.map(mapper::toGrantDto));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<List<CouponGrantDto>> directGrant(
            @PathVariable UUID publicId,
            @Valid @RequestBody DirectGrantRequest req) {
        List<CouponGrant> created = service.directGrant(publicId, req.userPublicIds());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(created.stream().map(mapper::toGrantDto).toList());
    }

    @PostMapping("/{grantPublicId}/revoke")
    @Transactional
    public CouponGrantDto revoke(
            @PathVariable UUID publicId,
            @PathVariable UUID grantPublicId) {
        return mapper.toGrantDto(service.revokeGrant(publicId, grantPublicId));
    }
}
