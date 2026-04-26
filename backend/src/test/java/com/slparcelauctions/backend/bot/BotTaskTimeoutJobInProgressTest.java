package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
 * Verifies the divergent IN_PROGRESS timeout behavior of
 * {@link BotTaskService#handleInProgressTimeout}: VERIFY flips to FAILED and
 * the auction back to VERIFICATION_FAILED; MONITOR_AUCTION re-arms to PENDING
 * instead of failing.
 *
 * <p>@UpdateTimestamp overwrites {@code lastUpdatedAt} on every
 * {@code save()}, so to simulate a stalled row we backdate the column via
 * raw JDBC after the initial save. Mirrors the raw-JDBC approach used in
 * {@link BotTaskClaimRaceIntegrationTest}.
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
        "slpa.bot-task.in-progress-timeout=PT1M",
        "slpa.notifications.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BotTaskTimeoutJobInProgressTest {

    @Autowired private BotTaskService service;
    @Autowired private BotTaskRepository botTaskRepo;
    @Autowired private AuctionRepository auctionRepo;
    @Autowired private ParcelRepository parcelRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private javax.sql.DataSource dataSource;

    private Long auctionId;
    private Long parcelId;
    private Long sellerId;
    private Long taskId;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                if (taskId != null) stmt.execute("DELETE FROM bot_tasks WHERE id = " + taskId);
                if (auctionId != null) {
                    stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (parcelId != null) stmt.execute("DELETE FROM parcels WHERE id = " + parcelId);
                if (sellerId != null) stmt.execute("DELETE FROM users WHERE id = " + sellerId);
            }
        }
        auctionId = null;
        parcelId = null;
        sellerId = null;
        taskId = null;
    }

    @Test
    void verifyTaskInProgressPastThreshold_failsAndFlipsAuction() throws Exception {
        Auction auction = seedAuction(AuctionStatus.VERIFICATION_PENDING);
        BotTask task = seedInProgressTask(auction, BotTaskType.VERIFY, null);
        backdateLastUpdated(task.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));

        List<BotTask> stalled = service.findInProgressOlderThan(Duration.ofMinutes(1));
        assertThat(stalled)
                .as("backdated IN_PROGRESS task is surfaced by findInProgressOlderThan")
                .extracting(BotTask::getId)
                .contains(task.getId());
        stalled.forEach(service::handleInProgressTimeout);

        BotTask reloaded = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("TIMEOUT (IN_PROGRESS)");
        assertThat(reloaded.getCompletedAt()).isNotNull();

        Auction reloadedAuction = auctionRepo.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus())
                .isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(reloadedAuction.getVerificationNotes())
                .contains("stalled mid-verify");
    }

    @Test
    void monitorAuctionTaskInProgressPastThreshold_reArmsToPending() throws Exception {
        Auction auction = seedAuction(AuctionStatus.ACTIVE);
        UUID assignedBot = UUID.randomUUID();
        BotTask task = seedInProgressTask(auction, BotTaskType.MONITOR_AUCTION, assignedBot);
        task.setRecurrenceIntervalSeconds(1800);
        botTaskRepo.save(task);
        backdateLastUpdated(task.getId(), OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));

        List<BotTask> stalled = service.findInProgressOlderThan(Duration.ofMinutes(1));
        assertThat(stalled)
                .extracting(BotTask::getId)
                .contains(task.getId());
        stalled.forEach(service::handleInProgressTimeout);

        BotTask reloaded = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BotTaskStatus.PENDING);
        assertThat(reloaded.getAssignedBotUuid())
                .as("MONITOR re-arm clears the assigned bot so a different worker can pick it up")
                .isNull();
        assertThat(reloaded.getNextRunAt()).isNotNull();
        assertThat(reloaded.getCompletedAt()).isNull();
        assertThat(reloaded.getFailureReason()).isNull();

        // Auction must be untouched — MONITOR re-arm is a retry, not a failure.
        Auction reloadedAuction = auctionRepo.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private Auction seedAuction(AuctionStatus status) {
        User seller = userRepo.save(User.builder()
                .email("bot-timeout-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Bot Timeout Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        Parcel parcel = parcelRepo.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .regionName("BotTimeoutRegion")
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
                .status(status)
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

    private BotTask seedInProgressTask(Auction auction, BotTaskType type, UUID assignedBot) {
        BotTask t = botTaskRepo.save(BotTask.builder()
                .taskType(type)
                .status(BotTaskStatus.IN_PROGRESS)
                .auction(auction)
                .parcelUuid(UUID.randomUUID())
                .sentinelPrice(999_999_999L)
                .assignedBotUuid(assignedBot)
                .build());
        this.taskId = t.getId();
        return t;
    }

    /**
     * {@code @UpdateTimestamp} rewrites {@code last_updated_at} on every
     * save, so we backdate it via raw JDBC to simulate a stalled row.
     */
    private void backdateLastUpdated(Long id, OffsetDateTime when) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var ps = conn.prepareStatement(
                    "UPDATE bot_tasks SET last_updated_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(when.toInstant()));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
    }
}
