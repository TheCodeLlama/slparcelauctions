package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.wallet.BidReservation;
import com.slparcelauctions.backend.wallet.BidReservationRepository;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.CapturingAuctionBroadcastPublisher;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;

/**
 * End-to-end coverage for the auction-end sweep against a real database.
 * Exercises the dev trigger endpoint ({@code POST /api/v1/dev/auction-end/run-once})
 * so the full transactional stack runs: scheduler query â†’ pessimistic lock
 * acquisition â†’ outcome classification â†’ status flip â†’ proxy exhaust â†’
 * afterCommit envelope publish.
 *
 * <p>The real scheduler is disabled via {@code slpa.auction-end.enabled=false}
 * to keep the cron tick from racing the explicit run-once invocation. The
 * {@link AuctionBroadcastPublisher} is swapped for a
 * {@link CapturingAuctionBroadcastPublisher} so the test can assert envelope
 * contents without a live STOMP broker.
 *
 * <p>The class is NOT {@code @Transactional}: the auction-end worker commits
 * on the caller thread and the test assertions read the committed state back.
 * Fixture rows are seeded inside an explicit {@link TransactionTemplate} and
 * cleaned up in {@link #cleanUp}.
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
@Import(AuctionEndIntegrationTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuctionEndIntegrationTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingAuctionBroadcastPublisher capturingPublisher() {
            return new CapturingAuctionBroadcastPublisher();
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
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired BidReservationRepository bidReservationRepo;
    @Autowired com.slparcelauctions.backend.wallet.UserLedgerRepository userLedgerRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired CapturingAuctionBroadcastPublisher capturingPublisher;

    private Long seededAuctionId;
    private Long seededSellerId;
    private Long seededBidderId;
    private UUID seededBidderPublicId;

    /**
     * Tears down seeded fixture rows via JPA repositories rather than raw
     * {@code DELETE} JDBC. The old approach named every child table by hand
     * ({@code bids}, {@code proxy_bids}, {@code auction_tags}, ...) which
     * silently stops covering new FK-children as Epic 05 lands (e.g.
     * {@code escrow_transactions}). Repository-driven cleanup:
     *
     * <ul>
     *   <li>Deletes each auction's children via {@code deleteAllByAuctionId}
     *       helpers on {@link BidRepository} and {@link ProxyBidRepository}.</li>
     *   <li>Deletes the auction via {@link AuctionRepository#delete}, which
     *       lets Hibernate clear the {@code auction_tags} {@code @ManyToMany}
     *       join-table rows as part of the entity delete.</li>
     *   <li>Deletes the parcel and users via their repositories, after
     *       draining any refresh-token / verification-code rows owned by the
     *       seeded users (defensive â€” the test doesn't create them, but a
     *       stray row must not block teardown of a parallel run).</li>
     * </ul>
     *
     * Wrapped in a {@link TransactionTemplate} so the entire teardown is a
     * single atomic unit; a partial failure cannot leave half-cleaned state
     * that corrupts the next {@code @Test} invocation.
     */
    @AfterEach
    void cleanUp() {
        capturingPublisher.reset();
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            // Escrow row (Epic 05 Task 2+) must be deleted BEFORE the auction
            // because of the FK from escrows.auction_id â†’ auctions.id.
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
                refreshTokenRepo.findAllByUserId(userId)
                        .forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        com.slparcelauctions.backend.verification.VerificationCodeType.PLAYER)
                        .forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        com.slparcelauctions.backend.verification.VerificationCodeType.PARCEL)
                        .forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userLedgerRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededBidderPublicId = null;
    }

    @Test
    void runOnce_closesExpiredAuction_withBidAboveReserve_outcomeSold() throws Exception {
        long currentBid = 2000L;
        long reserve = 1000L;
        int bidCount = 2;
        seedExpiredAuction(currentBid, reserve, bidCount, "Winner Avatar");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed", org.hamcrest.Matchers.hasItem(seededAuctionId.intValue())));

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        // SOLD close: EscrowService.createForEndedAuction flips ACTIVE -> TRANSFER_PENDING
        // after auto-funding from the winner's bid reservation.
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.TRANSFER_PENDING);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(refreshed.getWinnerUserId()).isEqualTo(seededBidderId);
        assertThat(refreshed.getFinalBidAmount()).isEqualTo(currentBid);
        assertThat(refreshed.getEndedAt()).isNotNull();

        assertThat(capturingPublisher.ended).hasSize(1);
        assertThat(capturingPublisher.ended.get(0).endOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(capturingPublisher.ended.get(0).winnerPublicId()).isEqualTo(seededBidderPublicId);
        assertThat(capturingPublisher.ended.get(0).winnerDisplayName()).isEqualTo("Winner Avatar");
        assertThat(capturingPublisher.ended.get(0).finalBid()).isEqualTo(currentBid);
        // Scheduler path must stamp serverTime from the same OffsetDateTime
        // it wrote to auction.endedAt â€” otherwise two OffsetDateTime.now(clock)
        // calls can drift microseconds under Clock.systemUTC() and break
        // client-side cross-channel event ordering. Postgres TIMESTAMPTZ can
        // round nanoseconds to microseconds (half-up), while the in-memory
        // envelope preserves nanoseconds â€” allow a 1Î¼s tolerance so the
        // assertion catches "different instant" regressions but not rounding.
        assertThat(capturingPublisher.ended.get(0).serverTime())
                .isCloseTo(refreshed.getEndedAt(), within(1, java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void runOnce_closesExpiredAuction_withBidBelowReserve_outcomeReserveNotMet() throws Exception {
        long currentBid = 500L;
        long reserve = 2000L;
        int bidCount = 1;
        seedExpiredAuction(currentBid, reserve, bidCount, "Below Reserve Bidder");

        mockMvc.perform(post("/api/v1/dev/auction-end/run-once"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed", org.hamcrest.Matchers.hasItem(seededAuctionId.intValue())));

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        // RESERVE_NOT_MET close: no escrow opens, status flips to EXPIRED directly.
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.EXPIRED);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(refreshed.getWinnerUserId()).isNull();
        assertThat(refreshed.getFinalBidAmount()).isNull();
        assertThat(refreshed.getEndedAt()).isNotNull();

        assertThat(capturingPublisher.ended).hasSize(1);
        assertThat(capturingPublisher.ended.get(0).endOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(capturingPublisher.ended.get(0).winnerPublicId()).isNull();
        assertThat(capturingPublisher.ended.get(0).winnerDisplayName()).isNull();
        assertThat(capturingPublisher.ended.get(0).finalBid()).isNull();
    }

    @Test
    void closeOneEndpoint_closesAuction_returnsClosedId() throws Exception {
        seedExpiredAuction(1500L, 1000L, 1, "Solo Winner");

        mockMvc.perform(post("/api/v1/dev/auctions/" + seededAuctionId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedId").value(seededAuctionId.intValue()));

        Auction refreshed = auctionRepo.findById(seededAuctionId).orElseThrow();
        // SOLD close: EscrowService.createForEndedAuction flips ACTIVE -> TRANSFER_PENDING.
        assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.TRANSFER_PENDING);
        assertThat(refreshed.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private void seedExpiredAuction(
            long currentBid, long reserve, int bidCount, String bidderDisplayName) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("end-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("End Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            // Wallet-only escrow funding (spec 2026-05-16): seed balance
            // + an active reservation so auto-fund-from-wallet at
            // auction-close lands the escrow at TRANSFER_PENDING for SOLD
            // outcomes. Below-reserve cases skip the reservation, so
            // reservedLindens must stay zero or reconciliation will
            // flag denorm-drift.
            boolean willSell = bidCount > 0 && currentBid > 0L && currentBid >= reserve;
            User bidder = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("end-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName(bidderDisplayName)
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .balanceLindens(currentBid > 0 ? currentBid : 1_000_000L)
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
                    .currentBidderId(bidder.getId())
                    .bidCount(bidCount)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .startsAt(now.minusHours(2))
                    // Already expired â€” the run-once sweep must pick this up.
                    .endsAt(now.minusSeconds(1))
                    .originalEndsAt(now.minusSeconds(1))
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("End Test Parcel")
                    .regionName("Test Region")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);

            // For SOLD outcomes (currentBid >= reserve and bidCount > 0)
            // createForEndedAuction auto-funds from the active
            // BidReservation, so seed a bid + reservation row.
            if (bidCount > 0 && currentBid >= reserve && currentBid > 0L) {
                Bid bid = bidRepo.save(Bid.builder()
                        .auction(auction)
                        .bidder(bidder)
                        .amount(currentBid)
                        .bidType(BidType.MANUAL)
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
            seededBidderPublicId = bidder.getPublicId();
            seededAuctionId = auction.getId();
        });
    }
}
