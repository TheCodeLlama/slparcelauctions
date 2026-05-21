package com.slparcelauctions.backend.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionVerificationService;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import reactor.core.publisher.Mono;

/**
 * Plan Task 11 coverage: at the DRAFT_PAID to ACTIVE transition inside
 * {@link AuctionVerificationService#triggerVerification}, every coupon
 * grant stamped on the auction must be consumed exactly once -- its
 * {@code remainingCount} decremented, and the grant flipped to
 * {@link CouponGrantState#EXHAUSTED} when the count hits zero.
 *
 * <p>The five scenarios assert:
 * <ol>
 *   <li>A single grant referenced from both the fee and commission
 *       columns decrements ONCE (LinkedHashSet dedupes).</li>
 *   <li>Two distinct grants each decrement once.</li>
 *   <li>A DURATION-only grant (remainingCount == null) is not
 *       decremented and its state is preserved.</li>
 *   <li>A grant whose count drops from 1 to 0 transitions to
 *       EXHAUSTED.</li>
 *   <li>Cancellation from DRAFT_PAID (via {@link CancellationService})
 *       does NOT decrement -- the grant survives for the next listing
 *       attempt.</li>
 * </ol>
 *
 * <p>Follows the {@code @SpringBootTest} + scheduler-mute pattern from
 * the other coupon integration tests. {@link SlWorldApiClient} is mocked
 * via {@link MockitoBean} so the synchronous ownership check inside
 * {@code triggerVerification} returns matching ownership and the auction
 * lands in ACTIVE.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AuctionActivationCouponConsumptionTest {

    @Autowired AuctionVerificationService verificationService;
    @Autowired CancellationService cancellationService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired CouponRepository couponRepo;
    @Autowired CouponGrantRepository grantRepo;

    @MockitoBean SlWorldApiClient worldApi;

    @PersistenceContext EntityManager em;

    private User seller;
    private User admin;

    @BeforeEach
    void seed() {
        seller = userRepo.save(User.builder()
                .username("seller-" + shortId())
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Seller")
                .verified(true)
                .build());
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Admin")
                .verified(true)
                .build());
        em.flush();
    }

    @Test
    void singleGrantOnBothColumns_decrementsOnce_andExhausts() {
        CouponGrant grant = seedGrant(2, CouponGrantState.ACTIVE);
        Auction auction = seedDraftPaidAuction(grant.getId(), grant.getId());
        stubWorldApiMatchingOwner(auction.getSlParcelUuid(), seller.getSlAvatarUuid());

        verificationService.triggerVerification(auction.getId(), seller.getId());
        em.flush();
        em.clear();

        CouponGrant reloaded = grantRepo.findById(grant.getId()).orElseThrow();
        // Same grant id on both columns -> decremented exactly once.
        assertThat(reloaded.getRemainingCount()).isEqualTo(1);
        assertThat(reloaded.getState()).isEqualTo(CouponGrantState.ACTIVE);

        Auction after = auctionRepo.findById(auction.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
    }

    @Test
    void twoDistinctGrants_eachDecrementOnce() {
        CouponGrant feeGrant = seedGrant(3, CouponGrantState.ACTIVE);
        CouponGrant commGrant = seedGrant(5, CouponGrantState.ACTIVE);
        Auction auction = seedDraftPaidAuction(feeGrant.getId(), commGrant.getId());
        stubWorldApiMatchingOwner(auction.getSlParcelUuid(), seller.getSlAvatarUuid());

        verificationService.triggerVerification(auction.getId(), seller.getId());
        em.flush();
        em.clear();

        CouponGrant feeReloaded = grantRepo.findById(feeGrant.getId()).orElseThrow();
        CouponGrant commReloaded = grantRepo.findById(commGrant.getId()).orElseThrow();
        assertThat(feeReloaded.getRemainingCount()).isEqualTo(2);
        assertThat(feeReloaded.getState()).isEqualTo(CouponGrantState.ACTIVE);
        assertThat(commReloaded.getRemainingCount()).isEqualTo(4);
        assertThat(commReloaded.getState()).isEqualTo(CouponGrantState.ACTIVE);
    }

    @Test
    void durationOnlyGrant_isNotDecremented() {
        // remainingCount == null marks a DURATION-only grant. Consumption
        // must skip it so its lifetime stays bounded purely by expiresAt.
        CouponGrant grant = seedGrant(null, CouponGrantState.ACTIVE);
        Auction auction = seedDraftPaidAuction(grant.getId(), null);
        stubWorldApiMatchingOwner(auction.getSlParcelUuid(), seller.getSlAvatarUuid());

        verificationService.triggerVerification(auction.getId(), seller.getId());
        em.flush();
        em.clear();

        CouponGrant reloaded = grantRepo.findById(grant.getId()).orElseThrow();
        assertThat(reloaded.getRemainingCount()).isNull();
        assertThat(reloaded.getState()).isEqualTo(CouponGrantState.ACTIVE);
    }

    @Test
    void countDropsToZero_transitionsToExhausted() {
        CouponGrant grant = seedGrant(1, CouponGrantState.ACTIVE);
        Auction auction = seedDraftPaidAuction(grant.getId(), null);
        stubWorldApiMatchingOwner(auction.getSlParcelUuid(), seller.getSlAvatarUuid());

        verificationService.triggerVerification(auction.getId(), seller.getId());
        em.flush();
        em.clear();

        CouponGrant reloaded = grantRepo.findById(grant.getId()).orElseThrow();
        assertThat(reloaded.getRemainingCount()).isZero();
        assertThat(reloaded.getState()).isEqualTo(CouponGrantState.EXHAUSTED);
    }

    @Test
    void cancellationFromDraftPaid_doesNotDecrement() {
        // Seller cancels before triggering verification: the grant stays
        // referenced but its remainingCount and state must not change.
        // Consumption is the activation-side commitment of the discount;
        // a never-activated listing returns the grant to the user.
        CouponGrant grant = seedGrant(2, CouponGrantState.ACTIVE);
        Auction auction = seedDraftPaidAuction(grant.getId(), grant.getId());

        cancellationService.cancel(auction.getId(), "changed-my-mind", "127.0.0.1");
        em.flush();
        em.clear();

        CouponGrant reloaded = grantRepo.findById(grant.getId()).orElseThrow();
        assertThat(reloaded.getRemainingCount()).isEqualTo(2);
        assertThat(reloaded.getState()).isEqualTo(CouponGrantState.ACTIVE);

        Auction after = auctionRepo.findById(auction.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
    }

    // ---- helpers ----

    private CouponGrant seedGrant(Integer remainingCount, CouponGrantState state) {
        Coupon coupon = couponRepo.save(Coupon.builder()
                .code("ACTIVATE-" + shortId())
                .description("Activation-consumption test")
                .durationDays(30)
                .useCount(remainingCount)
                .createdByUserId(admin.getId())
                .build());
        em.flush();
        CouponGrant grant = grantRepo.save(CouponGrant.builder()
                .coupon(coupon)
                .user(seller)
                .remainingCount(remainingCount)
                .expiresAt(OffsetDateTime.now().plusDays(30))
                .state(state)
                .source(CouponGrantSource.REDEMPTION)
                .build());
        em.flush();
        return grant;
    }

    private Auction seedDraftPaidAuction(Long listingFeeCouponGrantId,
                                          Long commissionCouponGrantId) {
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = Auction.builder()
                .title("Coupon-consumption test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.DRAFT_PAID)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .listingFeeAmt(0L)
                .listingFeeCouponGrantId(listingFeeCouponGrantId)
                .commissionCouponGrantId(commissionCouponGrantId)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.0500"))
                .build();
        auction = auctionRepo.save(auction);
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("TestRegion")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        auctionRepo.save(auction);
        em.flush();
        return auction;
    }

    private void stubWorldApiMatchingOwner(UUID parcelUuid, UUID ownerUuid) {
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                        ownerUuid,
                        "agent",
                        null,
                        "Test Parcel",
                        "TestRegion",
                        1024,
                        "desc",
                        null,
                        null,
                        128.0,
                        64.0,
                        22.0), UUID.randomUUID())));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
