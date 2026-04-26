package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Verifies that two parallel {@code claim()} calls race cleanly over
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}: one gets task A, the other
 * gets task B, neither blocks. Regression guard for FOOTGUNS §F.86.
 *
 * <p>Uses the shared dev Postgres — {@code FOR UPDATE SKIP LOCKED} is
 * Postgres-specific syntax, which the project runs exclusively on. Each
 * service.claim() call opens its own transaction (propagation REQUIRED
 * inside a daemon thread with no enclosing tx), so the row lock acquired
 * in one thread genuinely prevents the other from claiming the same row.
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
        "slpa.notifications.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BotTaskClaimRaceIntegrationTest {

    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private BotTaskService service;
    @Autowired private AuctionRepository auctionRepo;
    @Autowired private ParcelRepository parcelRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private javax.sql.DataSource dataSource;

    private Long auctionId;
    private Long parcelId;
    private Long sellerId;
    private Long t1Id;
    private Long t2Id;

    @AfterEach
    void cleanup() throws Exception {
        // Raw JDBC cleanup — the test itself is not @Transactional because
        // the two claim threads need their own independent transactions.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                if (t1Id != null) stmt.execute("DELETE FROM bot_tasks WHERE id = " + t1Id);
                if (t2Id != null) stmt.execute("DELETE FROM bot_tasks WHERE id = " + t2Id);
                if (auctionId != null) {
                    stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (parcelId != null) {
                    stmt.execute("DELETE FROM parcels WHERE id = " + parcelId);
                }
                if (sellerId != null) {
                    stmt.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        auctionId = null;
        parcelId = null;
        sellerId = null;
        t1Id = null;
        t2Id = null;
    }

    @Test
    void twoConcurrentClaims_returnDifferentTasks_noDeadlock() throws Exception {
        // Seed: a fake auction + 2 PENDING tasks.
        Auction auction = seedAuction();
        BotTask t1 = botTaskRepo.save(BotTask.builder()
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .build());
        BotTask t2 = botTaskRepo.save(BotTask.builder()
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .build());
        this.t1Id = t1.getId();
        this.t2Id = t2.getId();

        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Optional<BotTask>> claimA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.claim(botA);
        };
        Callable<Optional<BotTask>> claimB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.claim(botB);
        };

        Future<Optional<BotTask>> fA = pool.submit(claimA);
        Future<Optional<BotTask>> fB = pool.submit(claimB);

        Optional<BotTask> a = fA.get(10, TimeUnit.SECONDS);
        Optional<BotTask> b = fB.get(10, TimeUnit.SECONDS);

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(a.get().getId()).isNotEqualTo(b.get().getId());
        assertThat(List.of(t1.getId(), t2.getId()))
                .contains(a.get().getId(), b.get().getId());

        BotTask reloadedA = botTaskRepo.findById(a.get().getId()).orElseThrow();
        BotTask reloadedB = botTaskRepo.findById(b.get().getId()).orElseThrow();
        assertThat(reloadedA.getStatus()).isEqualTo(BotTaskStatus.IN_PROGRESS);
        assertThat(reloadedB.getStatus()).isEqualTo(BotTaskStatus.IN_PROGRESS);
        assertThat(reloadedA.getAssignedBotUuid()).isIn(botA, botB);
        assertThat(reloadedB.getAssignedBotUuid()).isIn(botA, botB);
        assertThat(reloadedA.getAssignedBotUuid())
                .isNotEqualTo(reloadedB.getAssignedBotUuid());
    }

    private Auction seedAuction() {
        User seller = userRepo.save(User.builder()
                .email("bot-claim-race-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Bot Claim Race Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        UUID parcelUuid = UUID.randomUUID();
        Parcel parcel = parcelRepo.save(Parcel.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .regionName("BotClaimRaceRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = auctionRepo.save(Auction.builder()
                .title("Test listing")
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.VERIFICATION_PENDING)
                .verificationMethod(VerificationMethod.SALE_TO_BOT)
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
                .createdAt(now)
                .updatedAt(now)
                .build());
        this.sellerId = seller.getId();
        this.parcelId = parcel.getId();
        this.auctionId = auction.getId();
        return auction;
    }
}
