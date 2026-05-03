package com.slparcelauctions.backend.auction.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.CancellationLogRepository;
import com.slparcelauctions.backend.auction.CancellationOffenseKind;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Epic 08 sub-spec 2 regression: two concurrent cancellations targeting two
 * different auctions for the SAME seller must serialise on the seller-row
 * pessimistic lock acquired inside {@link CancellationService#cancel}. The
 * critical invariant is that the COUNT-before-INSERT ladder evaluation
 * observes consistent prior-offense counts — without the seller-row lock,
 * both transactions could read {@code countPriorOffensesWithBids = 0}
 * concurrently and both snapshot the same ladder index (WARNING + WARNING),
 * skipping the PENALTY rung.
 *
 * <p>Mirrors the harness style of {@link BidCancelRaceTest}: NOT
 * {@code @Transactional}, so each thread runs its own transaction and the
 * row lock actually contends. Both auctions are ACTIVE-with-bids so both
 * cancellations qualify for the ladder.
 *
 * <p>Accepted orderings (post-serialisation, after both threads finish):
 * <ol>
 *   <li>One log row records {@code WARNING}, the other records
 *       {@code PENALTY} (with {@code amountL = 1000}).</li>
 *   <li>The seller's {@code penaltyBalanceOwed} is exactly {@code 1000}
 *       (the PENALTY contributed) and {@code cancelledWithBids} is exactly
 *       {@code 2}.</li>
 * </ol>
 * If the lock were missing, both threads could serialise as WARNING+WARNING
 * (debt would be 0 — the spec violation we're guarding against).
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CancelLadderRaceTest {

    @Autowired CancellationService cancellationService;
    @Autowired CancellationLogRepository cancellationLogRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired DataSource dataSource;

    @MockitoBean AuctionBroadcastPublisher publisher;

    private Long sellerId;
    private Long auctionId1;
    private Long auctionId2;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                if (auctionId1 != null) {
                    stmt.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId1);
                    stmt.execute("DELETE FROM bids WHERE auction_id = " + auctionId1);
                    stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId1);
                    stmt.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId1);
                    stmt.execute("DELETE FROM auctions WHERE id = " + auctionId1);
                }
                if (auctionId2 != null) {
                    stmt.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId2);
                    stmt.execute("DELETE FROM bids WHERE auction_id = " + auctionId2);
                    stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId2);
                    stmt.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId2);
                    stmt.execute("DELETE FROM auctions WHERE id = " + auctionId2);
                }
                if (sellerId != null) {
                    stmt.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = null;
        auctionId1 = null;
        auctionId2 = null;
    }

    @Test
    void cancel_concurrent_serializesViaSellerRowLock() throws Exception {
        setup();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();

        Runnable cancel1 = () -> {
            ready.countDown();
            try {
                go.await();
                cancellationService.cancel(auctionId1, "race-1", null);
            } catch (Throwable t) {
                err1.set(t);
            }
        };
        Runnable cancel2 = () -> {
            ready.countDown();
            try {
                go.await();
                cancellationService.cancel(auctionId2, "race-2", null);
            } catch (Throwable t) {
                err2.set(t);
            }
        };

        Thread t1 = new Thread(cancel1, "race-cancel-1");
        Thread t2 = new Thread(cancel2, "race-cancel-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        t1.join(TimeUnit.SECONDS.toMillis(20));
        t2.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(err1.get()).as("cancel-1 must not throw").isNull();
        assertThat(err2.get()).as("cancel-2 must not throw").isNull();

        // Both auctions cancelled.
        assertThat(auctionRepository.findById(auctionId1).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.CANCELLED);
        assertThat(auctionRepository.findById(auctionId2).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.CANCELLED);

        // Counter incremented exactly twice.
        User reloaded = userRepository.findById(sellerId).orElseThrow();
        assertThat(reloaded.getCancelledWithBids())
                .as("cancelled_with_bids must increment exactly twice")
                .isEqualTo(2);

        // The seller-row lock must serialise the COUNT, so the two log rows
        // must record DISTINCT ladder kinds — one WARNING, one PENALTY. The
        // PENALTY contributes 1000 L$ debt; WARNING contributes nothing.
        long warningCount = cancellationLogRepository.findAll().stream()
                .filter(l -> l.getSeller().getId().equals(sellerId))
                .filter(l -> l.getPenaltyKind() == CancellationOffenseKind.WARNING)
                .count();
        long penaltyCount = cancellationLogRepository.findAll().stream()
                .filter(l -> l.getSeller().getId().equals(sellerId))
                .filter(l -> l.getPenaltyKind() == CancellationOffenseKind.PENALTY)
                .count();
        assertThat(warningCount)
                .as("exactly one WARNING ladder snapshot must land")
                .isEqualTo(1);
        assertThat(penaltyCount)
                .as("exactly one PENALTY ladder snapshot must land — without the seller-row lock both could land as WARNING")
                .isEqualTo(1);

        assertThat(reloaded.getPenaltyBalanceOwed())
                .as("PENALTY contributes 1000 L$ debt; WARNING contributes nothing")
                .isEqualTo(1000L);
    }

    private void setup() {
        User seller = userRepository.save(User.builder()
                .email("cancel-ladder-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Cancel Ladder Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .bannedFromListing(false)
                .build());
        UUID parcelUuid1 = UUID.randomUUID();
        UUID parcelUuid2 = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a1 = auctionRepository.save(Auction.builder()
                .title("Race auction 1")
                .slParcelUuid(parcelUuid1).seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L).durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true).listingFeeAmt(0L)
                .currentBid(1_500L).bidCount(1)
                .consecutiveWorldApiFailures(0)
                .startsAt(now.minusHours(1))
                .endsAt(now.plusDays(1))
                .originalEndsAt(now.plusDays(1))
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());
        a1.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid1)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Race Parcel 1")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        auctionRepository.save(a1);

        Auction a2 = auctionRepository.save(Auction.builder()
                .title("Race auction 2")
                .slParcelUuid(parcelUuid2).seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L).durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true).listingFeeAmt(0L)
                .currentBid(1_500L).bidCount(1)
                .consecutiveWorldApiFailures(0)
                .startsAt(now.minusHours(1))
                .endsAt(now.plusDays(1))
                .originalEndsAt(now.plusDays(1))
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());
        a2.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid2)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Race Parcel 2")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        auctionRepository.save(a2);

        this.sellerId = seller.getId();
        this.auctionId1 = a1.getId();
        this.auctionId2 = a2.getId();
    }
}
