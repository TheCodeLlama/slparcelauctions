package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Race regression for the Task 6 close path vs the snipe-extending bid path.
 * Two threads drive their own transactions against the same auction:
 * <ul>
 *   <li>Thread A — {@link BidService#placeBid} inside the snipe window, which
 *       pushes {@code endsAt} forward by the window size.</li>
 *   <li>Thread B — {@link AuctionEndTask#closeOne}, which re-checks the three
 *       gates under a pessimistic lock before closing.</li>
 * </ul>
 *
 * <p>Both orderings are valid outcomes:
 * <ol>
 *   <li>Bid commits first → scheduler re-reads the extended {@code endsAt},
 *       sees it in the future, skips the close. Auction stays ACTIVE.</li>
 *   <li>Scheduler commits first → bid path takes the lock after commit, sees
 *       status=ENDED, throws {@link InvalidAuctionStateException} (or if the
 *       scheduler had not yet flipped the status but endsAt was already in
 *       the past, {@link AuctionAlreadyEndedException}).</li>
 * </ol>
 *
 * <p>Exactly one operation must succeed. The other must surface a deterministic
 * failure — never a double close or a silent no-op on both sides.
 *
 * <p><strong>Critical:</strong> this class is NOT {@code @Transactional};
 * both threads use explicit {@link TransactionTemplate}s. The fixture rows
 * are committed in {@code setup} and cleaned up in {@link #cleanup}. The
 * auction-end cron is disabled via {@code slpa.auction-end.enabled=false}
 * so the only close path exercised is the explicit one in the test.
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
class BidSchedulerRaceTest {

    @Autowired BidService bidService;
    @Autowired AuctionEndTask auctionEndTask;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.slparcelauctions.backend.notification.NotificationRepository notificationRepository;
    @Autowired PlatformTransactionManager txManager;

    // The real publisher is a STOMP broker that isn't wired in the unit test
    // slice. Mock it so afterCommit does not blow up.
    @MockitoBean AuctionBroadcastPublisher publisher;

    private Long auctionId;
    private Long sellerId;
    private Long bidderId;

    @AfterEach
    void cleanup() {
        TransactionTemplate cleanup = new TransactionTemplate(txManager);
        cleanup.executeWithoutResult(status -> {
            if (auctionId != null) {
                auctionRepository.findById(auctionId).ifPresent(a -> {
                    bidRepository.findByAuctionIdOrderByCreatedAtAsc(a.getId())
                            .forEach(bidRepository::delete);
                    auctionRepository.delete(a);
                });
            }
            if (bidderId != null) {
                notificationRepository.deleteAllByUserId(bidderId);
                userRepository.findById(bidderId).ifPresent(userRepository::delete);
            }
            if (sellerId != null) {
                notificationRepository.deleteAllByUserId(sellerId);
                userRepository.findById(sellerId).ifPresent(userRepository::delete);
            }
        });
        auctionId = null;
        bidderId = null;
        sellerId = null;
    }

    @Test
    void snipeBid_vs_schedulerClose_exactlyOneSucceeds() throws Exception {
        // Auction endsAt is ONE SECOND AWAY — well inside a 5-minute snipe
        // window — so the bid will extend it. The scheduler's query sees the
        // row as due the moment now() crosses endsAt, racing the bid's lock
        // acquisition.
        setup(/* snipeMinutes */ 5);

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> bidError = new AtomicReference<>();
        AtomicReference<Throwable> closeError = new AtomicReference<>();
        AtomicReference<Boolean> bidSucceeded = new AtomicReference<>(false);
        AtomicReference<Boolean> closeSucceeded = new AtomicReference<>(false);

        Runnable bidAttempt = () -> {
            ready.countDown();
            try {
                go.await();
                // Sleep a touch so endsAt is confidently in the past from
                // the scheduler's perspective — BidService uses the server
                // clock for its own re-check.
                Thread.sleep(1200);
                txTemplate.execute(tx -> {
                    bidService.placeBid(auctionId, bidderId, 1500L, "1.2.3.4");
                    return null;
                });
                bidSucceeded.set(true);
            } catch (Throwable t) {
                bidError.set(unwrap(t));
            }
        };

        Runnable closeAttempt = () -> {
            ready.countDown();
            try {
                go.await();
                Thread.sleep(1200);
                auctionEndTask.closeOne(auctionId);
                closeSucceeded.set(true);
            } catch (Throwable t) {
                closeError.set(unwrap(t));
            }
        };

        Thread bidder = new Thread(bidAttempt, "race-bidder");
        Thread closer = new Thread(closeAttempt, "race-closer");
        bidder.setDaemon(true);
        closer.setDaemon(true);
        bidder.start();
        closer.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        bidder.join(TimeUnit.SECONDS.toMillis(20));
        closer.join(TimeUnit.SECONDS.toMillis(20));

        // Reload the final committed state.
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();

        // Deterministic outcome: either the bid extended endsAt (status still
        // ACTIVE, no close published) OR the scheduler closed the auction
        // (status ENDED, bid rejected with AuctionAlreadyEndedException or
        // InvalidAuctionStateException).
        if (reloaded.getStatus() == AuctionStatus.ACTIVE) {
            // Bid won the race. The scheduler's re-check must have seen
            // endsAt in the future and skipped — closeOne returned cleanly,
            // so closeSucceeded is true but no mutation occurred.
            assertThat(bidSucceeded.get())
                    .as("bid must have succeeded when auction stays ACTIVE")
                    .isTrue();
            assertThat(reloaded.getEndsAt()).isAfterOrEqualTo(reloaded.getOriginalEndsAt());
            assertThat(reloaded.getBidCount()).isEqualTo(1);
            assertThat(reloaded.getEndOutcome()).isNull();
        } else {
            // Scheduler won. Exactly one of the two threads committed the
            // close; the bidder must have surfaced one of the two 409s.
            assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ENDED);
            assertThat(reloaded.getEndOutcome()).isNotNull();
            assertThat(closeSucceeded.get())
                    .as("closeOne must have returned cleanly when it wins the race")
                    .isTrue();
            assertThat(bidSucceeded.get())
                    .as("bid must NOT have committed when the scheduler closed first")
                    .isFalse();
            Throwable e = bidError.get();
            assertThat(e)
                    .as("bid must fail deterministically when the close commits first; got %s", e)
                    .satisfiesAnyOf(
                            t -> assertThat(t).isInstanceOf(AuctionAlreadyEndedException.class),
                            t -> assertThat(t).isInstanceOf(InvalidAuctionStateException.class));
        }

        // In all outcomes, exactly one state-changing commit happened. Close
        // never partially mutates: if it committed, endOutcome is set; if it
        // didn't, endOutcome is null.
        assertThat(reloaded.getBidCount())
                .as("bidCount reflects the single winning path")
                .isIn(0, 1);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof AuctionAlreadyEndedException
                    || cur instanceof InvalidAuctionStateException) {
                return cur;
            }
            cur = cur.getCause();
        }
        return t;
    }

    private void setup(int snipeMinutes) {
        User seller = userRepository.save(User.builder()
                .email("race-end-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Race End Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        User bidder = userRepository.save(User.builder()
                .email("race-end-bidder-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Race End Bidder")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        // endsAt is 1 second in the future at setup time; by the time both
        // threads race past their 1.2s barrier, the clock has crossed it.
        OffsetDateTime endsAt = now.plusSeconds(1);
        Auction auction = auctionRepository.save(Auction.builder()
                .title("Test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(true)
                .snipeWindowMin(snipeMinutes)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .startsAt(now.minusHours(1))
                .endsAt(endsAt)
                .originalEndsAt(endsAt)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());

        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Race Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        auctionRepository.save(auction);

        this.sellerId = seller.getId();
        this.bidderId = bidder.getId();
        this.auctionId = auction.getId();
    }

}
