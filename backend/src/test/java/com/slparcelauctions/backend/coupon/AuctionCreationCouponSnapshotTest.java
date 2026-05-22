package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionService;
import com.slparcelauctions.backend.auction.ParcelSnapshotPhotoService;
import com.slparcelauctions.backend.auction.DefaultCoverPhotoService;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.escrow.payment.ListingFeePaymentService;
import com.slparcelauctions.backend.parcel.ParcelLookupService;
import com.slparcelauctions.backend.parcel.ParcelLookupService.ParcelLookupResult;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.testsupport.TestRegions;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Plan Task 10 coverage: every listing-create call routes through
 * {@link CouponDiscountResolver#resolve(long)} and the snapshot's
 * fee + commission + grant ids are stamped onto the new {@link Auction}
 * row. Mirrors {@code AuctionServiceTest}'s Mockito-only shape so we
 * unit-test the wiring without standing up the full Spring context.
 */
@ExtendWith(MockitoExtension.class)
class AuctionCreationCouponSnapshotTest {

    @Mock AuctionRepository auctionRepo;
    @Mock ParcelLookupService parcelLookupService;
    @Mock ParcelSnapshotPhotoService parcelSnapshotPhotoService;
    @Mock DefaultCoverPhotoService defaultCoverPhotoService;
    @Mock UserRepository userRepo;
    @Mock ParcelTagRepository tagRepo;
    @Mock BanCheckService banCheckService;
    @Mock CouponDiscountResolver couponDiscountResolver;
    @Mock ListingFeePaymentService listingFeePaymentService;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-05-20T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks AuctionService service;

    private static final UUID PARCEL_UUID = UUID.randomUUID();
    private User seller;

    @BeforeEach
    void setUp() {
        seller = User.builder().id(42L).email("s@example.com").username("s").verified(true).build();
        lenient().when(userRepo.findById(42L)).thenReturn(Optional.of(seller));

        Region region = TestRegions.mainland();
        ParcelResponse response = new ParcelResponse(
                PARCEL_UUID, UUID.randomUUID(), "agent", null, "Test Parcel",
                null, "TestRegion", "GENERAL", 1014.0, 1014.0,
                128.0, 64.0, 22.0, 1024, null, null, null, true, null, null);
        lenient().when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupResult(response, region));
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> {
            Auction a = inv.getArgument(0);
            setBaseEntityField(a, "id", 1L);
            return a;
        });
    }

    /**
     * Seller has no active coupon grants: the resolver returns the
     * configured defaults (L$100, 5%, both grant ids null). The Auction
     * row should mirror those exactly.
     */
    @Test
    void noGrants_stampsDefaultsAndNullGrantIds() {
        when(couponDiscountResolver.resolve(anyLong()))
                .thenReturn(new CouponDiscountResolver.DiscountSnapshot(
                        100L, new BigDecimal("0.05"), null, null));

        Auction created = service.create(42L, minimalCreateRequest(), null);

        assertThat(created.getListingFeeAmt()).isEqualTo(100L);
        assertThat(created.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(created.getListingFeeCouponGrantId()).isNull();
        assertThat(created.getCommissionCouponGrantId()).isNull();
        // Resolver MUST be consulted for every create: the invariant the
        // listing-fee + commission stamps depend on.
        verify(couponDiscountResolver).resolve(42L);
        // Non-zero fee → autoPay is a no-op but we still always call it,
        // since the guard lives inside the service. Verify the wiring
        // ran the call.
        verify(listingFeePaymentService).autoPayIfFreeAfterCreation(any(Auction.class));
    }

    /**
     * LISTING_FEE-only grant resolving to L$0: the Auction's
     * {@code listingFeeAmt} drops to 0 and only
     * {@code listingFeeCouponGrantId} is stamped; the commission stays
     * at the default with a null commissionCouponGrantId.
     */
    @Test
    void listingFeeOnlyGrant_stampsFeeIdNullCommissionId() {
        when(couponDiscountResolver.resolve(anyLong()))
                .thenReturn(new CouponDiscountResolver.DiscountSnapshot(
                        0L, new BigDecimal("0.05"), 7L, null));

        Auction created = service.create(42L, minimalCreateRequest(), null);

        assertThat(created.getListingFeeAmt()).isZero();
        assertThat(created.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(created.getListingFeeCouponGrantId()).isEqualTo(7L);
        assertThat(created.getCommissionCouponGrantId()).isNull();
    }

    /**
     * Multi-discount bundle: a single grant carrying both LISTING_FEE
     * and COMMISSION_RATE lines stamps the same grant id on both
     * Auction columns. Mirrors {@link CouponDiscountResolverTest#
     * singleGrant_bothTargets_stampsSameGrantId()}.
     */
    @Test
    void bundledGrant_stampsSameGrantIdOnBothColumns() {
        when(couponDiscountResolver.resolve(anyLong()))
                .thenReturn(new CouponDiscountResolver.DiscountSnapshot(
                        0L, new BigDecimal("0.03"), 42L, 42L));

        Auction created = service.create(42L, minimalCreateRequest(), null);

        assertThat(created.getListingFeeAmt()).isZero();
        assertThat(created.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.03"));
        assertThat(created.getListingFeeCouponGrantId()).isEqualTo(42L);
        assertThat(created.getCommissionCouponGrantId()).isEqualTo(42L);
    }

    /**
     * COMMISSION_RATE-only grant: the listing fee stays at the default
     * but the commission drops and only commissionCouponGrantId is
     * stamped.
     */
    @Test
    void commissionOnlyGrant_stampsCommissionIdNullFeeId() {
        when(couponDiscountResolver.resolve(anyLong()))
                .thenReturn(new CouponDiscountResolver.DiscountSnapshot(
                        100L, new BigDecimal("0.02"), null, 9L));

        Auction created = service.create(42L, minimalCreateRequest(), null);

        assertThat(created.getListingFeeAmt()).isEqualTo(100L);
        assertThat(created.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.02"));
        assertThat(created.getListingFeeCouponGrantId()).isNull();
        assertThat(created.getCommissionCouponGrantId()).isEqualTo(9L);
    }

    /**
     * The resolver must be called with the authenticated seller's id,
     * never some other value, otherwise an attacker who can write
     * through the service could lift another user's coupon discounts.
     */
    @Test
    void resolverConsultedWithSellerId() {
        when(couponDiscountResolver.resolve(anyLong()))
                .thenReturn(new CouponDiscountResolver.DiscountSnapshot(
                        100L, new BigDecimal("0.05"), null, null));

        service.create(42L, minimalCreateRequest(), null);

        verify(couponDiscountResolver).resolve(42L);
        verify(couponDiscountResolver, never()).resolve(99L);
    }

    private AuctionCreateRequest minimalCreateRequest() {
        return new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, false, null, null, Set.of(), null);
    }

    private static void setBaseEntityField(Object entity, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f =
                    com.slparcelauctions.backend.common.BaseEntity.class
                            .getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(entity, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
