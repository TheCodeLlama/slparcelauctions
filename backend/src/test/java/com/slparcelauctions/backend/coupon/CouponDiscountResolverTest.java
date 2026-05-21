package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CouponDiscountResolverTest {

    private CouponGrantRepository repo;
    private CouponDiscountResolver resolver;

    @BeforeEach
    void setUp() {
        repo = mock(CouponGrantRepository.class);
        resolver = new CouponDiscountResolver(repo);
        ReflectionTestUtils.setField(resolver, "defaultFeeLindens", 100L);
        ReflectionTestUtils.setField(resolver, "defaultCommissionRate", new BigDecimal("0.05"));
    }

    @Test
    void noGrants_returnsDefaults() {
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), eq(CouponGrantState.ACTIVE)))
                .thenReturn(List.of());
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeLindens()).isEqualTo(100L);
        assertThat(snap.commissionRate()).isEqualByComparingTo("0.05");
        assertThat(snap.listingFeeCouponGrantId()).isNull();
        assertThat(snap.commissionCouponGrantId()).isNull();
    }

    @Test
    void singleGrant_bothTargets_stampsSameGrantId() {
        CouponGrant g = grantWithBundle(42L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"),
                new BundleLine(DiscountTarget.COMMISSION_RATE, DiscountOp.OVERRIDE, "3.0"));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any())).thenReturn(List.of(g));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeLindens()).isZero();
        assertThat(snap.commissionRate()).isEqualByComparingTo("0.03");
        assertThat(snap.listingFeeCouponGrantId()).isEqualTo(42L);
        assertThat(snap.commissionCouponGrantId()).isEqualTo(42L);
    }

    @Test
    void tiebreak_noCountPreferred() {
        CouponGrant withCount = grantWithBundle(1L, false,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        withCount.setGrantedAt(OffsetDateTime.now().minusDays(10));
        CouponGrant withoutCount = grantWithBundle(2L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        withoutCount.setGrantedAt(OffsetDateTime.now().minusDays(1));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any()))
                .thenReturn(List.of(withCount, withoutCount));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeCouponGrantId()).isEqualTo(2L);
    }

    @Test
    void tiebreak_fifoByGrantedAt() {
        CouponGrant older = grantWithBundle(1L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        older.setGrantedAt(OffsetDateTime.now().minusDays(10));
        CouponGrant newer = grantWithBundle(2L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        newer.setGrantedAt(OffsetDateTime.now().minusDays(1));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any()))
                .thenReturn(List.of(older, newer));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeCouponGrantId()).isEqualTo(1L);
    }

    @Test
    void expiredByClock_butActiveByDb_isIgnored() {
        CouponGrant stale = grantWithBundle(99L, true,
                new BundleLine(DiscountTarget.LISTING_FEE, DiscountOp.OVERRIDE, "0"));
        stale.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(repo.findByUserIdAndStateOrderByGrantedAtAsc(anyLong(), any())).thenReturn(List.of(stale));
        var snap = resolver.resolve(1L);
        assertThat(snap.listingFeeCouponGrantId()).isNull();
    }

    record BundleLine(DiscountTarget target, DiscountOp op, String value) {}

    private CouponGrant grantWithBundle(long grantId, boolean noCount, BundleLine... lines) {
        Coupon c = new Coupon();
        ReflectionTestUtils.setField(c, "id", 100L + grantId);
        List<CouponDiscount> ds = Arrays.stream(lines).map(l -> CouponDiscount.builder()
                .coupon(c).target(l.target()).op(l.op()).value(new BigDecimal(l.value()))
                .build()).toList();
        c.setDiscounts(ds);
        CouponGrant g = CouponGrant.builder()
                .coupon(c)
                .state(CouponGrantState.ACTIVE)
                .grantedAt(OffsetDateTime.now())
                .remainingCount(noCount ? null : 1)
                .build();
        ReflectionTestUtils.setField(g, "id", grantId);
        return g;
    }
}
