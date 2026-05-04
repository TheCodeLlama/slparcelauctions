package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.dto.BidResponse;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCreatedEnvelope;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * End-to-end coverage for Escrow row creation on the inline buy-it-now close
 * path in {@link BidService#placeBid}. Complements {@code BidServiceBuyNowTest}
 * (unit-level Mockito coverage) by exercising the full transactional stack
 * against a real Postgres: pessimistic lock → bid insert → snipe + buy-now
 * evaluation → auction save → escrow stamp → afterCommit ESCROW_CREATED
 * publish.
 *
 * <p>Unlike {@code SnipeAndBuyNowIntegrationTest} (which is {@code @Transactional}
 * and rolls back at test end), this test commits both the fixture and the
 * bid so the escrow row can be read back in a separate transaction and the
 * afterCommit broadcast actually fires. The {@link EscrowBroadcastPublisher}
 * is swapped for {@link CapturingEscrowBroadcastPublisher} to assert envelope
 * contents without a live STOMP broker.
 *
 * <p>Teardown deletes the escrow row before the auction row because of the
 * FK {@code escrows.auction_id → auctions.id}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Import(EscrowCreateOnBuyNowIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowCreateOnBuyNowIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired BidService bidService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededSellerId;
    private Long seededBidderId;
    private java.util.UUID seededAuctionPublicId;

    @BeforeEach
    void resetCapture() {
        capturingEscrowPublisher.reset();
    }

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId)
                        .forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER)
                        .forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL)
                        .forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededSellerId = null;
        seededBidderId = null;
    }

    @Test
    void buyNowTriggerCreatesEscrowRowAndBroadcasts() {
        // buyNowPrice = L$10000. A bid at exactly L$10000 closes the auction
        // with BOUGHT_NOW and must land an ESCROW_PENDING row with the
        // 5% commission (L$500 clears the L$50 floor) + L$9500 payout.
        long bidAmount = 10_000L;
        seedActiveAuction(/* buyNowPrice */ bidAmount);

        // Place the buy-now bid on the seeded bidder's account. BidService
        // runs inside its own @Transactional boundary; afterCommit fires on
        // return, so the capturingEscrowPublisher has the envelope by the
        // time placeBid returns to the test.
        BidResponse resp = bidService.placeBid(
                seededAuctionId, seededBidderId, bidAmount, "1.2.3.4");

        assertThat(resp.buyNowTriggered()).isTrue();

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.BOUGHT_NOW);
        assertThat(refreshed.getFinalBidAmount()).isEqualTo(bidAmount);
        assertThat(refreshed.getWinnerUserId()).isEqualTo(seededBidderId);

        Escrow escrow = escrowRepo.findByAuctionId(seededAuctionId).orElseThrow();
        assertThat(escrow.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(escrow.getFinalBidAmount()).isEqualTo(bidAmount);
        // Commission math lives in EscrowCommissionCalculator (spec §4.3 —
        // max(bid * 5%, L$50) floor). Assert against the calculator so
        // test expectations track the business rule instead of duplicating
        // its arithmetic. At Phase-1 rates 10000*0.05 = 500 clears the L$50
        // floor, so commissionAmt=500 and payoutAmt=9500 — kept in the
        // comment so a reviewer sees the expected numeric value too.
        assertThat(escrow.getCommissionAmt())
                .isEqualTo(commissionCalculator.commission(bidAmount));
        assertThat(escrow.getPayoutAmt())
                .isEqualTo(commissionCalculator.payout(bidAmount));
        assertThat(escrow.getConsecutiveWorldApiFailures()).isZero();
        assertThat(escrow.getTransferDeadline()).isNull();
        assertThat(escrow.getFundedAt()).isNull();
        // paymentDeadline, auction.endedAt, and the envelope serverTime are
        // all anchored to the same `now` read in BidService.placeBid (step
        // 3); it's threaded through applySnipeAndBuyNow (endedAt),
        // createForEndedAuction (paymentDeadline = now + 48h), and
        // AuctionEndedEnvelope.of(..., now) (serverTime). So
        // paymentDeadline == endedAt + 48h exactly, modulo Postgres
        // TIMESTAMPTZ nanosecond→microsecond rounding on the persisted
        // column (1μs tolerance covers it).
        assertThat(escrow.getPaymentDeadline())
                .isCloseTo(refreshed.getEndedAt().plusHours(48), within(1, ChronoUnit.MICROS));

        assertThat(capturingEscrowPublisher.created).hasSize(1);
        EscrowCreatedEnvelope env = capturingEscrowPublisher.created.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_CREATED");
        assertThat(env.auctionPublicId()).isEqualTo(seededAuctionPublicId);
        assertThat(env.escrowPublicId()).isEqualTo(escrow.getPublicId());
        assertThat(env.state()).isEqualTo(EscrowState.ESCROW_PENDING);
        // Envelope serverTime + 48h = paymentDeadline — both derive from the
        // SAME `now` read so this round-trips exactly (modulo 1μs Postgres
        // TIMESTAMPTZ rounding on the persisted paymentDeadline side).
        assertThat(env.paymentDeadline())
                .isCloseTo(escrow.getPaymentDeadline(), within(1, ChronoUnit.MICROS));
        assertThat(escrow.getPaymentDeadline())
                .isCloseTo(env.serverTime().plusHours(48), within(1, ChronoUnit.MICROS));
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedActiveAuction(Long buyNowPrice) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder()
                    .email("escrow-buynow-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow BuyNow Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("escrow-buynow-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow BuyNow Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .currentBid(0L)
                    .bidCount(0)
                    .buyNowPrice(buyNowPrice)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(1))
                    .endsAt(now.plusHours(1))
                    .originalEndsAt(now.plusHours(1))
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("BuyNow Test Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);
            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededAuctionPublicId = auction.getPublicId();
        });
    }
}
