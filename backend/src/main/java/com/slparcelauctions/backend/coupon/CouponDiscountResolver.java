package com.slparcelauctions.backend.coupon;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Picks the best discount line per target across a user's active coupon
 * grants and produces the {@link DiscountSnapshot} stamped onto a new
 * auction at listing creation.
 *
 * <p>"Best" = lowest resulting fee (LISTING_FEE) or lowest resulting rate
 * (COMMISSION_RATE). Tiebreaks, in order:
 * <ol>
 *   <li>Grants with {@code remainingCount IS NULL} (DURATION-style) win
 *       over grants with a finite {@code remainingCount} (COUNT-style),
 *       so finite uses are preserved when an equivalent unlimited grant
 *       exists.</li>
 *   <li>FIFO by {@code grantedAt} (earliest wins).</li>
 * </ol>
 *
 * <p>Filters in-memory by clock as well as DB state: a grant past its
 * {@code expiresAt} that the hourly sweeper has not flipped to EXPIRED
 * yet is treated as expired here. Same for {@code remainingCount <= 0},
 * which shouldn't happen but is defensive.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-20-coupon-codes-design.md} section 3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CouponDiscountResolver {

    private final CouponGrantRepository grantRepository;

    @Value("${slpa.listing-fee.amount-lindens:100}")
    private long defaultFeeLindens;

    @Value("${slpa.commission.default-rate:0.05}")
    private BigDecimal defaultCommissionRate;

    /**
     * Result of {@link #resolve(long)}: the listing fee + commission rate
     * that should apply right now, plus the grant id stamped on each
     * line (null when no coupon applies and the default is in effect).
     */
    public record DiscountSnapshot(
            long listingFeeLindens,
            BigDecimal commissionRate,
            Long listingFeeCouponGrantId,
            Long commissionCouponGrantId
    ) {}

    public DiscountSnapshot resolve(long userId) {
        List<CouponGrant> active = grantRepository.findByUserIdAndStateOrderByGrantedAtAsc(
                userId, CouponGrantState.ACTIVE);
        OffsetDateTime now = OffsetDateTime.now();
        List<CouponGrant> usable = active.stream()
                .filter(g -> g.getExpiresAt() == null || g.getExpiresAt().isAfter(now))
                .filter(g -> g.getRemainingCount() == null || g.getRemainingCount() > 0)
                .toList();

        ResolvedLine listingFee = pickBest(usable, DiscountTarget.LISTING_FEE,
                defaultFeeLindens, defaultCommissionRate);
        ResolvedLine commission = pickBest(usable, DiscountTarget.COMMISSION_RATE,
                defaultFeeLindens, defaultCommissionRate);

        return new DiscountSnapshot(
                listingFee != null ? listingFee.feeLindens : defaultFeeLindens,
                commission != null ? commission.commissionRate : defaultCommissionRate,
                listingFee != null ? listingFee.grantId : null,
                commission != null ? commission.grantId : null
        );
    }

    private record ResolvedLine(Long grantId, long feeLindens, BigDecimal commissionRate,
                                boolean noUseCount, OffsetDateTime grantedAt) {}

    private ResolvedLine pickBest(List<CouponGrant> grants, DiscountTarget target,
                                  long defaultFee, BigDecimal defaultRate) {
        ResolvedLine best = null;
        for (CouponGrant g : grants) {
            for (CouponDiscount d : g.getCoupon().getDiscounts()) {
                if (d.getTarget() != target) continue;
                ResolvedLine candidate;
                if (target == DiscountTarget.LISTING_FEE) {
                    long fee = CouponDiscountCalculator.applyListingFee(d.getOp(), d.getValue(), defaultFee);
                    candidate = new ResolvedLine(g.getId(), fee, defaultRate,
                            g.getRemainingCount() == null, g.getGrantedAt());
                } else {
                    BigDecimal rate = CouponDiscountCalculator.applyCommission(d.getOp(), d.getValue(), defaultRate);
                    candidate = new ResolvedLine(g.getId(), defaultFee, rate,
                            g.getRemainingCount() == null, g.getGrantedAt());
                }
                if (isBetter(candidate, best, target)) best = candidate;
            }
        }
        return best;
    }

    private boolean isBetter(ResolvedLine candidate, ResolvedLine current, DiscountTarget target) {
        if (current == null) return true;
        int cmp = target == DiscountTarget.LISTING_FEE
                ? Long.compare(candidate.feeLindens, current.feeLindens)
                : candidate.commissionRate.compareTo(current.commissionRate);
        if (cmp != 0) return cmp < 0;
        // Tiebreak 1: no-count wins
        if (candidate.noUseCount != current.noUseCount) return candidate.noUseCount;
        // Tiebreak 2: FIFO (earlier granted_at wins)
        return candidate.grantedAt.isBefore(current.grantedAt);
    }
}
