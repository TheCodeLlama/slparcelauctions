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
import com.slparcelauctions.backend.wallet.BidReservationRepository;

/**
 * End-to-end coverage for Escrow row creation on the inline buy-it-now close
 * path in {@link BidService#placeBid}. Complements {@code BidServiceBuyNowTest}
 * (unit-level Mockito coverage) by exercising the full transactional stack
 * against a real Postgres: pessimistic lock â†’ bid insert â†’ snipe + buy-now
 * evaluation â†’ auction save â†’ escrow stamp â†’ afterCommit ESCROW_CREATED
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
 * FK {@code escrows.auction_id â†’ auctions.id}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
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
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired NotificationRepository notificationRepo;
    @Autowired BidReservationRepository bidReservationRepo;
    @Autowired com.slparcelauctions.backend.wallet.UserLedgerRepository userLedgerRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired javax.sql.DataSource dataSource;
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
            bidReservationRepo.findAll().stream()
                    .filter(r -> seededAuctionId.equals(r.getAuctionId()))
                    .forEach(bidReservationRepo::delete);
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrow -> {
                escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(escrow.getId())
                        .forEach(escrowTxRepo::delete);
                escrowRepo.delete(escrow);
            });
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                userLedgerRepo.deleteAllByUserId(userId);
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
        // Wallet-only escrow funding (spec 2026-05-16): the auto-fund
        // path runs in the same transaction as createForEndedAuction, so
        // observers see the row at TRANSFER_PENDING (FUNDED is an
        // intermediate transition).
        assertThat(escrow.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(escrow.getFinalBidAmount()).isEqualTo(bidAmount);
        assertThat(escrow.getCommissionAmt())
                .isEqualTo(commissionCalculator.commission(bidAmount));
        assertThat(escrow.getPayoutAmt())
                .isEqualTo(commissionCalculator.payout(bidAmount));
        assertThat(escrow.getConsecutiveWorldApiFailures()).isZero();
        assertThat(escrow.getFundedAt()).isNotNull();
        // transferDeadline = fundedAt + 72h.
        assertThat(escrow.getTransferDeadline())
                .isCloseTo(escrow.getFundedAt().plusHours(72), within(1, ChronoUnit.MICROS));

        assertThat(capturingEscrowPublisher.created).hasSize(1);
        EscrowCreatedEnvelope env = capturingEscrowPublisher.created.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_CREATED");
        assertThat(env.auctionPublicId()).isEqualTo(seededAuctionPublicId);
        assertThat(env.escrowPublicId()).isEqualTo(escrow.getPublicId());
        // The ESCROW_CREATED envelope is registered before the auto-fund
        // transition, so it carries the initial ESCROW_PENDING state.
        assertThat(env.state()).isEqualTo(EscrowState.ESCROW_PENDING);
    }

    @Test
    void buyNow_doesNotPublishEscrowFundedNotification_forSeller() {
        // On the buy-now path BidService.acceptBid publishes a dedicated
        // AUCTION_ENDED_BOUGHT_NOW notification to the seller; the same
        // transaction's wallet auto-fund used to also publish ESCROW_FUNDED,
        // landing two near-duplicate seller rows ("Buy-now exercised" +
        // "Buyer funded escrow on …") for a single event. Only the
        // BOUGHT_NOW notification should fire on this path.
        long bidAmount = 10_000L;
        seedActiveAuction(bidAmount);

        bidService.placeBid(seededAuctionId, seededBidderId, bidAmount, "1.2.3.4");

        java.util.List<com.slparcelauctions.backend.notification.Notification> sellerNotifs =
                notificationRepo.findAllByUserId(seededSellerId);
        assertThat(sellerNotifs)
                .extracting(com.slparcelauctions.backend.notification.Notification::getCategory)
                .contains(com.slparcelauctions.backend.notification.NotificationCategory.AUCTION_ENDED_BOUGHT_NOW)
                .doesNotContain(com.slparcelauctions.backend.notification.NotificationCategory.ESCROW_FUNDED);
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedActiveAuction(Long buyNowPrice) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-buynow-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow BuyNow Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-buynow-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow BuyNow Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    // Wallet-only escrow funding: balance must cover the
                    // buy-now bid so the wallet precondition + reservation
                    // swap pass, then auto-fund consumes the reservation.
                    .balanceLindens(1_000_000L)
                    .reservedLindens(0L)
                    .penaltyBalanceOwed(0L)
                    .build());
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)

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
