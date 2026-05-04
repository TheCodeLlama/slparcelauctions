package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
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
 * End-to-end coverage for Escrow row creation on auction-end (SOLD outcome).
 * Exercises the dev trigger endpoint {@code POST /api/v1/dev/auction-end/run-once}
 * so the full transactional stack runs: scheduler query → pessimistic lock →
 * outcome classification → status flip → escrow stamp → afterCommit
 * ESCROW_CREATED publish.
 *
 * <p>The real scheduler is disabled via {@code slpa.auction-end.enabled=false}
 * to keep the cron tick from racing the explicit run-once invocation. The
 * {@link EscrowBroadcastPublisher} is swapped for a
 * {@link CapturingEscrowBroadcastPublisher} so assertions can inspect
 * envelope contents without a live STOMP broker.
 *
 * <p>Seeding mirrors {@code AuctionEndIntegrationTest}: explicit
 * {@link TransactionTemplate} write, repository-driven teardown so new
 * child tables (including {@code escrows}) don't silently escape cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Import(EscrowCreateOnAuctionEndIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowCreateOnAuctionEndIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired MockMvc mockMvc;
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
            // Escrow row (if any) must be deleted BEFORE the auction because
            // of the FK from escrows.auction_id → auctions.id.
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
    void soldOutcomeCreatesEscrowRowAndBroadcasts() throws Exception {
        // L$5000 > L$1000 reserve → SOLD. Commission: floor(5000*5/100)=250
        // clears the L$50 floor, so commissionAmt=250, payoutAmt=4750.
        long currentBid = 5_000L;
        long reserve = 1_000L;
        int bidCount = 2;
        seedExpiredAuction(currentBid, reserve, bidCount, "Winner Avatar");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk());

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(refreshed.getFinalBidAmount()).isEqualTo(currentBid);
        assertThat(refreshed.getEndedAt()).isNotNull();

        Escrow escrow = escrowRepo.findByAuctionId(seededAuctionId).orElseThrow();
        assertThat(escrow.getState()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(escrow.getFinalBidAmount()).isEqualTo(currentBid);
        // Commission math lives in EscrowCommissionCalculator (spec §4.3 —
        // max(bid * 5%, L$50) floor). Assert against the calculator so
        // test expectations track the business rule instead of duplicating
        // its arithmetic. At Phase-1 rates 5000*0.05 = 250 clears the L$50
        // floor, so commissionAmt=250 and payoutAmt=4750 — kept in the
        // comment so a reviewer sees the expected numeric value too.
        assertThat(escrow.getCommissionAmt())
                .isEqualTo(commissionCalculator.commission(currentBid));
        assertThat(escrow.getPayoutAmt())
                .isEqualTo(commissionCalculator.payout(currentBid));
        assertThat(escrow.getConsecutiveWorldApiFailures()).isZero();
        // paymentDeadline = endedAt + 48h. Allow 1μs tolerance for Postgres
        // TIMESTAMPTZ nanosecond→microsecond rounding.
        assertThat(escrow.getPaymentDeadline())
                .isCloseTo(refreshed.getEndedAt().plusHours(48), within(1, ChronoUnit.MICROS));
        assertThat(escrow.getTransferDeadline()).isNull();
        assertThat(escrow.getFundedAt()).isNull();

        assertThat(capturingEscrowPublisher.created).hasSize(1);
        EscrowCreatedEnvelope env = capturingEscrowPublisher.created.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_CREATED");
        assertThat(env.auctionPublicId()).isEqualTo(seededAuctionPublicId);
        assertThat(env.escrowPublicId()).isEqualTo(escrow.getPublicId());
        assertThat(env.state()).isEqualTo(EscrowState.ESCROW_PENDING);
        assertThat(env.paymentDeadline())
                .isCloseTo(escrow.getPaymentDeadline(), within(1, ChronoUnit.MICROS));
        // serverTime should match the auction's endedAt exactly — both come
        // from the same OffsetDateTime.now(clock) call in closeOne.
        assertThat(env.serverTime())
                .isCloseTo(refreshed.getEndedAt(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void noBidsOutcomeDoesNotCreateEscrow() throws Exception {
        // bidCount=0 classifies as NO_BIDS — no escrow row, no envelope.
        seedExpiredAuction(0L, 1_000L, 0, "No Bidder");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk());

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.NO_BIDS);

        assertThat(escrowRepo.findByAuctionId(seededAuctionId)).isEmpty();
        assertThat(capturingEscrowPublisher.created).isEmpty();
    }

    @Test
    void reserveNotMetOutcomeDoesNotCreateEscrow() throws Exception {
        // currentBid=500 < reserve=2000 → RESERVE_NOT_MET. No escrow row.
        seedExpiredAuction(500L, 2_000L, 1, "Below Reserve");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk());

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ENDED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);

        assertThat(escrowRepo.findByAuctionId(seededAuctionId)).isEmpty();
        assertThat(capturingEscrowPublisher.created).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding — mirrors AuctionEndIntegrationTest so the fixture shape stays
    // consistent across close-path coverage.
    // -------------------------------------------------------------------------

    private void seedExpiredAuction(
            long currentBid, long reserve, int bidCount, String bidderDisplayName) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder()
                    .email("escrow-end-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow End Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            User bidder = userRepo.save(User.builder()
                    .email("escrow-end-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName(bidderDisplayName)
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
                    .reservePrice(reserve)
                    .currentBid(currentBid)
                    .currentBidderId(bidCount == 0 ? null : bidder.getId())
                    .bidCount(bidCount)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(2))
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Escrow Test Parcel")
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
