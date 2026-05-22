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
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.wallet.BidReservation;
import com.slparcelauctions.backend.wallet.BidReservationRepository;
// EscrowTransactionRepository is in this package
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCreatedEnvelope;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * End-to-end coverage for Escrow row creation on auction-end (SOLD outcome).
 * Exercises the dev trigger endpoint {@code POST /api/v1/dev/auction-end/run-once}
 * so the full transactional stack runs: scheduler query â†’ pessimistic lock â†’
 * outcome classification â†’ status flip â†’ escrow stamp â†’ afterCommit
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
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
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
    @Autowired EscrowService escrowService;
    @Autowired com.slparcelauctions.backend.bot.BotTaskRepository botTaskRepo;
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
            // Escrow row (if any) must be deleted BEFORE the auction because
            // of the FK from escrows.auction_id â†’ auctions.id.
            bidReservationRepo.findAll().stream()
                    .filter(r -> seededAuctionId.equals(r.getAuctionId()))
                    .forEach(bidReservationRepo::delete);
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrow -> {
                escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(escrow.getId())
                        .forEach(escrowTxRepo::delete);
                // VERIFY_SELL_TO bot task is created at funding (spec
                // 2026-05-17) — clear it before the escrow to satisfy the
                // bot_tasks.escrow_id FK.
                botTaskRepo.findByEscrowId(escrow.getId())
                        .forEach(botTaskRepo::delete);
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
    void soldOutcomeCreatesEscrowRowAndBroadcasts() throws Exception {
        // L$5000 > L$1000 reserve â†’ SOLD. Commission: floor(5000*5/100)=250
        // clears the L$50 floor, so commissionAmt=250, payoutAmt=4750.
        long currentBid = 5_000L;
        long reserve = 1_000L;
        int bidCount = 2;
        seedExpiredAuction(currentBid, reserve, bidCount, "Winner Avatar");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk());

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        // SOLD close: EscrowService.createForEndedAuction flips status
        // to TRANSFER_PENDING after auto-funding from winner's reservation.
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.TRANSFER_PENDING);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(refreshed.getFinalBidAmount()).isEqualTo(currentBid);
        assertThat(refreshed.getEndedAt()).isNotNull();

        Escrow escrow = escrowRepo.findByAuctionId(seededAuctionId).orElseThrow();
        // Wallet-only escrow funding (spec 2026-05-16): createForEndedAuction
        // auto-funds from the winner's bid reservation in the same
        // transaction, so external observers see TRANSFER_PENDING.
        assertThat(escrow.getState()).isEqualTo(EscrowState.TRANSFER_PENDING);
        assertThat(escrow.getFinalBidAmount()).isEqualTo(currentBid);
        assertThat(escrow.getCommissionAmt())
                .isEqualTo(commissionCalculator.commission(currentBid, new java.math.BigDecimal("0.0500")));
        assertThat(escrow.getPayoutAmt())
                .isEqualTo(commissionCalculator.payout(currentBid, new java.math.BigDecimal("0.0500")));
        assertThat(escrow.getConsecutiveWorldApiFailures()).isZero();
        // transferDeadline = fundedAt + 72h; both stamped during auto-fund.
        assertThat(escrow.getFundedAt()).isNotNull();
        assertThat(escrow.getTransferDeadline())
                .isCloseTo(escrow.getFundedAt().plusHours(72), within(1, ChronoUnit.MICROS));

        // Status DTO for an individual sale must NOT carry group-sale fields.
        EscrowStatusResponse statusResp = escrowService.getStatus(
                seededAuctionId, seededSellerId);
        assertThat(statusResp.agentCommissionAmt()).isNull();
        assertThat(statusResp.groupSliceAmt()).isNull();
        assertThat(statusResp.groupName()).isNull();

        assertThat(capturingEscrowPublisher.created).hasSize(1);
        EscrowCreatedEnvelope env = capturingEscrowPublisher.created.get(0);
        assertThat(env.type()).isEqualTo("ESCROW_CREATED");
        assertThat(env.auctionPublicId()).isEqualTo(seededAuctionPublicId);
        assertThat(env.escrowPublicId()).isEqualTo(escrow.getPublicId());
        // The ESCROW_CREATED envelope is emitted with the row's initial
        // state (ESCROW_PENDING) before the same-transaction auto-fund.
        assertThat(env.state()).isEqualTo(EscrowState.ESCROW_PENDING);
        // serverTime should match the auction's endedAt exactly â€” both come
        // from the same OffsetDateTime.now(clock) call in closeOne.
        assertThat(env.serverTime())
                .isCloseTo(refreshed.getEndedAt(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void noBidsOutcomeDoesNotCreateEscrow() throws Exception {
        // bidCount=0 classifies as NO_BIDS â€” no escrow row, no envelope.
        seedExpiredAuction(0L, 1_000L, 0, "No Bidder");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk());

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        // NO_BIDS close: no escrow opens, status flips to EXPIRED directly.
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.EXPIRED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.NO_BIDS);

        assertThat(escrowRepo.findByAuctionId(seededAuctionId)).isEmpty();
        assertThat(capturingEscrowPublisher.created).isEmpty();
    }

    @Test
    void reserveNotMetOutcomeDoesNotCreateEscrow() throws Exception {
        // currentBid=500 < reserve=2000 â†’ RESERVE_NOT_MET. No escrow row.
        seedExpiredAuction(500L, 2_000L, 1, "Below Reserve");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk());

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        // RESERVE_NOT_MET close: no escrow opens, status flips to EXPIRED directly.
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.EXPIRED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);

        assertThat(escrowRepo.findByAuctionId(seededAuctionId)).isEmpty();
        assertThat(capturingEscrowPublisher.created).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Seeding â€” mirrors AuctionEndIntegrationTest so the fixture shape stays
    // consistent across close-path coverage.
    // -------------------------------------------------------------------------

    private void seedExpiredAuction(
            long currentBid, long reserve, int bidCount, String bidderDisplayName) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-end-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Escrow End Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            // Wallet-only escrow funding (spec 2026-05-16): seed enough
            // balance + an active reservation so createForEndedAuction
            // can debit + transition to TRANSFER_PENDING.
            // Only the SOLD path needs reservedLindens; NO_BIDS and
            // RESERVE_NOT_MET cases leave reservedLindens=0 so the
            // reconciliation invariant holds across the suite.
            boolean willSell = bidCount > 0 && currentBid > 0L && currentBid >= reserve;
            User bidder = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("escrow-end-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName(bidderDisplayName)
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .balanceLindens(currentBid > 0L ? currentBid : 0L)
                    .reservedLindens(willSell ? currentBid : 0L)
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
                    .reservePrice(reserve)
                    .currentBid(currentBid)
                    .currentBidderId(bidCount == 0 ? null : bidder.getId())
                    .bidCount(bidCount)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
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

            // For SOLD outcomes auto-fund consumes a BidReservation; seed
            // a bid row + reservation only when the auction will actually
            // close as SOLD (currentBid >= reserve) so the reconciliation
            // invariant (sum of reservedLindens == sum of active
            // reservations) stays intact across the test suite.
            if (willSell) {
                Bid bid = bidRepo.save(Bid.builder()
                        .auction(auction)
                        .bidder(bidder)
                        .amount(currentBid)
                        .bidType(BidType.MANUAL)
                        .ipAddress("127.0.0.1")
                        .build());
                bidReservationRepo.save(BidReservation.builder()
                        .userId(bidder.getId())
                        .auctionId(auction.getId())
                        .bidId(bid.getId())
                        .amount(currentBid)
                        .build());
            }

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededAuctionPublicId = auction.getPublicId();
        });
    }
}
