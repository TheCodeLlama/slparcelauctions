package com.slparcelauctions.backend.coupon;

import java.util.List;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.coupon.dto.CouponDiscountDto;
import com.slparcelauctions.backend.coupon.dto.CouponGrantDto;
import com.slparcelauctions.backend.coupon.dto.ProspectiveDiscountsDto;

import lombok.RequiredArgsConstructor;

/**
 * Entity-to-DTO conversion for the coupon slice. Kept as a singleton
 * {@code @Component} so controllers can inject one helper rather than
 * each duplicating the mapping logic; admin endpoints (Task 13) will
 * extend this class with {@code toDto} / {@code toSummary} methods that
 * cover the admin views.
 */
@Component
@RequiredArgsConstructor
public class CouponMapper {

    private final CouponGrantRepository grantRepo;

    /**
     * Builds a {@link CouponGrantDto} from a grant plus its parent
     * coupon's discount lines. The wallet view consumes this shape; the
     * frontend reads the flattened discount list to render the create-
     * listing summary card without a second fetch.
     */
    public CouponGrantDto toGrantDto(CouponGrant g) {
        Coupon c = g.getCoupon();
        List<CouponDiscountDto> ds = c.getDiscounts().stream()
                .map(d -> new CouponDiscountDto(d.getTarget(), d.getOp(), d.getValue(), d.getSortOrder()))
                .toList();
        return new CouponGrantDto(
                g.getPublicId(),
                c.getPublicId(),
                c.getCode(),
                g.getGrantedAt(),
                g.getExpiresAt(),
                g.getRemainingCount(),
                g.getState(),
                g.getSource(),
                ds);
    }

    /**
     * Enriches a {@link CouponDiscountResolver.DiscountSnapshot} with the
     * winning grants' coupon public-id + code so the create-listing UI
     * can attribute each discount to a specific coupon. Either field may
     * be null when no coupon won that target (default rate is in effect).
     */
    public ProspectiveDiscountsDto toProspective(CouponDiscountResolver.DiscountSnapshot snap) {
        Coupon listingFeeCoupon = snap.listingFeeCouponGrantId() != null
                ? grantRepo.findById(snap.listingFeeCouponGrantId())
                        .map(CouponGrant::getCoupon)
                        .orElse(null)
                : null;
        Coupon commissionCoupon = snap.commissionCouponGrantId() != null
                ? grantRepo.findById(snap.commissionCouponGrantId())
                        .map(CouponGrant::getCoupon)
                        .orElse(null)
                : null;
        return new ProspectiveDiscountsDto(
                snap.listingFeeLindens(),
                snap.commissionRate(),
                listingFeeCoupon != null ? listingFeeCoupon.getPublicId() : null,
                listingFeeCoupon != null ? listingFeeCoupon.getCode() : null,
                commissionCoupon != null ? commissionCoupon.getPublicId() : null,
                commissionCoupon != null ? commissionCoupon.getCode() : null);
    }
}
