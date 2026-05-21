package com.slparcelauctions.backend.coupon;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.coupon.dto.CouponGrantDto;
import com.slparcelauctions.backend.coupon.dto.ProspectiveDiscountsDto;
import com.slparcelauctions.backend.coupon.dto.RedeemCouponRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * User-facing coupon endpoints. JWT-authenticated via the global
 * {@code /api/v1/**} catch-all; user identity is taken from the
 * principal, never client-supplied.
 *
 * <ul>
 *   <li>{@code GET /api/v1/me/coupons?filter=active|history} - list a
 *       user's grants. {@code active} (default) returns ACTIVE rows
 *       FIFO by {@code grantedAt}; {@code history} returns non-ACTIVE
 *       rows most-recent-first.</li>
 *   <li>{@code POST /api/v1/me/coupons/redeem} - redeem a user-typed
 *       code. Returns 201 + the created {@link CouponGrantDto}. Any
 *       {@link CouponException} is mapped to RFC 9457 {@code ProblemDetail}
 *       by {@link CouponExceptionHandler}.</li>
 *   <li>{@code GET /api/v1/me/listings/prospective-discounts} - the
 *       create-listing summary card's data source: which listing-fee +
 *       commission rate would apply if the user created a listing
 *       right now, plus the attributing coupon code(s).</li>
 * </ul>
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-20-coupon-codes-design.md}
 * section 7.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeCouponController {

    private final CouponService couponService;
    private final CouponDiscountResolver resolver;
    private final CouponGrantRepository grantRepo;
    private final CouponMapper mapper;

    // @Transactional on every controller method that maps a grant
    // outside the service: {@code mapper.toGrantDto} dereferences
    // {@code grant.coupon} (LAZY ManyToOne) and then {@code
    // coupon.discounts} (LAZY OneToMany), both of which throw
    // LazyInitializationException once the service tx closes. The
    // outer @Transactional keeps the session open through the mapper.

    @GetMapping("/coupons")
    @Transactional(readOnly = true)
    public List<CouponGrantDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "active") String filter) {
        if ("history".equalsIgnoreCase(filter)) {
            return grantRepo.findByUserIdOrderByGrantedAtDesc(principal.userId()).stream()
                    .filter(g -> g.getState() != CouponGrantState.ACTIVE)
                    .map(mapper::toGrantDto)
                    .toList();
        }
        return grantRepo.findByUserIdAndStateOrderByGrantedAtAsc(
                        principal.userId(), CouponGrantState.ACTIVE).stream()
                .map(mapper::toGrantDto)
                .toList();
    }

    @PostMapping("/coupons/redeem")
    @Transactional
    public ResponseEntity<CouponGrantDto> redeem(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RedeemCouponRequest req) {
        CouponGrant grant = couponService.redeem(principal.userId(), req.code());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toGrantDto(grant));
    }

    @GetMapping("/listings/prospective-discounts")
    @Transactional(readOnly = true)
    public ProspectiveDiscountsDto prospective(@AuthenticationPrincipal AuthPrincipal principal) {
        CouponDiscountResolver.DiscountSnapshot snap = resolver.resolve(principal.userId());
        return mapper.toProspective(snap);
    }
}
