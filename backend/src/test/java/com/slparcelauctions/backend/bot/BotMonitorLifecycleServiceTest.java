package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for {@link BotMonitorLifecycleService} — the lifecycle
 * hooks that create MONITOR_AUCTION / MONITOR_ESCROW rows on activation and
 * cancel them on terminal state. Exercises the real Flyway schema so the
 * {@code @Modifying} bulk-cancel queries fire against Postgres and the
 * {@code lastUpdatedAt} SET-clause keeps the column NOT-NULL-safe.
 *
 * <p>Uses raw-JDBC cleanup (dev Postgres) rather than {@code @Transactional}
 * rollback — the lifecycle service methods carry their own transactions and
 * {@code @Modifying} queries commit outside any caller-rolled-back boundary.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.escrow.listing-fee-refund-job.enabled=false",
        "slpa.bot-task.timeout-check-interval=PT10M",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BotMonitorLifecycleServiceTest {

    @Autowired private BotMonitorLifecycleService lifecycle;
    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private AuctionRepository auctionRepo;
    @Autowired private EscrowRepository escrowRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private javax.sql.DataSource dataSource;

    private Long sellerId;
    private Long winnerId;
    private Long auctionId;
    private Long escrowId;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                if (auctionId != null) {
                    stmt.execute("DELETE FROM bot_tasks WHERE auction_id = " + auctionId);
                }
                if (escrowId != null) {
                    stmt.execute("DELETE FROM escrows WHERE id = " + escrowId);
                }
                if (auctionId != null) {
                    stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (winnerId != null) {
                    stmt.execute("DELETE FROM users WHERE id = " + winnerId);
                }
                if (sellerId != null) {
                    stmt.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = null;
        winnerId = null;
        auctionId = null;
        escrowId = null;
    }

    @Test
    void onAuctionActivatedBot_createsMonitorRow() {
        Auction auction = seedAuction(VerificationTier.BOT, AuctionStatus.ACTIVE, false);

        lifecycle.onAuctionActivatedBot(auction);

        List<BotTask> rows = botTaskRepo.findAll().stream()
                .filter(r -> r.getTaskType() == BotTaskType.MONITOR_AUCTION)
                .filter(r -> r.getAuction().getId().equals(auction.getId()))
                .toList();
        assertThat(rows).hasSize(1);
        BotTask monitor = rows.get(0);
        assertThat(monitor.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(monitor.getExpectedOwnerUuid())
                .isEqualTo(auction.getParcelSnapshot().getOwnerUuid());
        // Default slpa.bot.monitor-auction-interval is PT30M = 1800 seconds.
        assertThat(monitor.getRecurrenceIntervalSeconds()).isEqualTo(1800);
        assertThat(monitor.getNextRunAt())
                .isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void onAuctionActivatedBot_skipsNonBotTier() {
        Auction auction = seedAuction(VerificationTier.SCRIPT, AuctionStatus.ACTIVE, false);

        lifecycle.onAuctionActivatedBot(auction);

        long count = botTaskRepo.findAll().stream()
                .filter(r -> r.getTaskType() == BotTaskType.MONITOR_AUCTION)
                .filter(r -> r.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(count).isZero();
    }

    @Test
    void onAuctionClosed_cancelsLiveRows() {
        Auction auction = seedAuction(VerificationTier.BOT, AuctionStatus.ACTIVE, false);
        lifecycle.onAuctionActivatedBot(auction);

        lifecycle.onAuctionClosed(auction);

        List<BotTask> cancelled = botTaskRepo.findAll().stream()
                .filter(r -> r.getAuction().getId().equals(auction.getId()))
                .filter(r -> r.getStatus() == BotTaskStatus.CANCELLED)
                .toList();
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0).getCompletedAt()).isNotNull();
        assertThat(cancelled.get(0).getUpdatedAt()).isNotNull();
    }

    @Test
    void onEscrowTerminal_cancelsEscrowMonitorRows() {
        Auction auction = seedAuction(VerificationTier.BOT, AuctionStatus.ENDED, true);
        seedEscrow(auction);

        // Both calls need to run with the escrow's Auction proxy eager-loaded.
        // The lifecycle methods open their own transaction but the detached
        // escrow we'd pass in carries a proxy with no session. Re-fetch
        // inside a TransactionTemplate so the Auction reference is resolved
        // against the active session before the hook dereferences it.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(ts -> {
            Escrow managed = escrowRepo.findById(escrowId).orElseThrow();
            // Touch the auction so the proxy initializes inside this tx.
            managed.getAuction().getVerificationTier();
            lifecycle.onEscrowCreatedBot(managed);
        });
        tx.executeWithoutResult(ts -> {
            Escrow managed = escrowRepo.findById(escrowId).orElseThrow();
            lifecycle.onEscrowTerminal(managed);
        });

        List<BotTask> cancelled = botTaskRepo.findAll().stream()
                .filter(r -> r.getEscrow() != null
                        && r.getEscrow().getId().equals(escrowId))
                .filter(r -> r.getStatus() == BotTaskStatus.CANCELLED)
                .toList();
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0).getCompletedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    private Auction seedAuction(
            VerificationTier tier, AuctionStatus status, boolean needsWinner) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(ts -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("bot-lifecycle-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Bot Lifecycle Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());
            sellerId = seller.getId();

            User winner = null;
            if (needsWinner) {
                winner = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                        .email("bot-lifecycle-winner-" + UUID.randomUUID() + "@example.com")
                        .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                        .displayName("Bot Lifecycle Winner")
                        .slAvatarUuid(UUID.randomUUID())
                        .verified(true)
                        .build());
                winnerId = winner.getId();
            }

            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction.AuctionBuilder b = Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(status)
                    .verificationMethod(VerificationMethod.SALE_TO_BOT)
                    .verificationTier(tier)
                    .verifiedAt(now)
                    .startingBid(1_000L)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .listingFeeAmt(100L)
                    .currentBid(0L)
                    .bidCount(0)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(2))
                    .endsAt(now.plusHours(166))
                    .originalEndsAt(now.plusHours(166))
                    .createdAt(now)
                    .updatedAt(now);
            if (winner != null) {
                b = b.winnerUserId(winner.getId())
                        .finalBidAmount(5_000L)
                        .endedAt(now);
            }
            Auction a = auctionRepo.save(b.build());
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                    .parcelName("Test Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(a);
            auctionId = a.getId();
        });
        return auctionRepo.findById(auctionId).orElseThrow();
    }

    private Escrow seedEscrow(Auction auction) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(ts -> {
            Escrow e = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.ESCROW_PENDING)
                    .finalBidAmount(5_000L)
                    .commissionAmt(250L)
                    .payoutAmt(4_750L)
                    .paymentDeadline(OffsetDateTime.now().plusHours(48))
                    .consecutiveWorldApiFailures(0)
                    .build());
            escrowId = e.getId();
        });
        return escrowRepo.findById(escrowId).orElseThrow();
    }
}
