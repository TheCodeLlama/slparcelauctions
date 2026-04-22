package com.slparcelauctions.backend.auction.concurrency;

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
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import javax.sql.DataSource;

/**
 * Epic 04 Task 7 regression: bid placement vs seller cancellation racing on
 * the same auction. With {@link CancellationService#cancel(Long, String)}
 * retrofitted onto {@link AuctionRepository#findByIdForUpdate}, both writers
 * serialise on the row lock so the loser observes the winner's committed
 * state and surfaces a deterministic failure (or commits cleanly when the
 * sequence is legal per spec §5).
 *
 * <p>Accepted orderings:
 * <ol>
 *   <li>Bid commits first, cancel takes the lock with status=ACTIVE and
 *       bidCount=1 → cancel succeeds; {@code cancelled_with_bids} counter
 *       increments on the seller.</li>
 *   <li>Cancel commits first, bid takes the lock with status=CANCELLED →
 *       bid surfaces {@link InvalidAuctionStateException}.</li>
 * </ol>
 *
 * <p>The critical invariant is that <em>exactly one of</em>
 * {@code bidCount} and {@code status=CANCELLED} came from the other writer
 * — they are never both silently no-ops and the auction's committed
 * {@code currentBid}/{@code bidCount} is never divorced from the
 * {@code bid} rows on disk.
 *
 * <p><strong>Critical:</strong> this class is NOT {@code @Transactional}.
 * Both threads drive their own {@link TransactionTemplate} so the bid path's
 * row lock actually races the cancel path's row lock; ambient test-level
 * transactions would serialise the two paths inside a single transaction and
 * mask the race.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BidCancelRaceTest {

    @Autowired BidService bidService;
    @Autowired CancellationService cancellationService;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    // Stub the STOMP publisher so BidService's afterCommit callback does not
    // trip on a missing broker under the concurrency harness.
    @MockitoBean AuctionBroadcastPublisher publisher;

    private Long auctionId;
    private Long sellerId;
    private Long bidderId;
    private Long parcelId;

    @AfterEach
    void cleanup() throws Exception {
        // Use raw JDBC so the cascade order is deterministic regardless of
        // which writer won the race. JPA cascade rules don't cover the
        // cancellation_logs / listing_fee_refunds audit rows, and the tests
        // need to survive both orderings without leaking fixture rows into
        // other test classes.
        if (auctionId != null) {
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (var stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM listing_fee_refunds WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM bids WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + auctionId);
                    stmt.execute("DELETE FROM auctions WHERE id = " + auctionId);
                    if (parcelId != null) {
                        stmt.execute("DELETE FROM parcels WHERE id = " + parcelId);
                    }
                    if (bidderId != null) {
                        stmt.execute("DELETE FROM users WHERE id = " + bidderId);
                    }
                    if (sellerId != null) {
                        stmt.execute("DELETE FROM users WHERE id = " + sellerId);
                    }
                }
            }
        }
        auctionId = null;
        parcelId = null;
        bidderId = null;
        sellerId = null;
    }

    @Test
    void concurrentBidAndCancel_serialisesOnRowLock() throws Exception {
        setup();

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> bidError = new AtomicReference<>();
        AtomicReference<Throwable> cancelError = new AtomicReference<>();
        AtomicReference<Boolean> bidSucceeded = new AtomicReference<>(false);
        AtomicReference<Boolean> cancelSucceeded = new AtomicReference<>(false);

        Runnable bidAttempt = () -> {
            ready.countDown();
            try {
                go.await();
                txTemplate.execute(tx -> {
                    bidService.placeBid(auctionId, bidderId, 1500L, "1.2.3.4");
                    return null;
                });
                bidSucceeded.set(true);
            } catch (Throwable t) {
                bidError.set(unwrap(t));
            }
        };

        Runnable cancelAttempt = () -> {
            ready.countDown();
            try {
                go.await();
                // CancellationService uses its own @Transactional; the test
                // drives it directly so both threads contend on the row lock.
                cancellationService.cancel(auctionId, "race-test");
                cancelSucceeded.set(true);
            } catch (Throwable t) {
                cancelError.set(unwrap(t));
            }
        };

        Thread bidder = new Thread(bidAttempt, "race-bidder");
        Thread canceller = new Thread(cancelAttempt, "race-canceller");
        bidder.setDaemon(true);
        canceller.setDaemon(true);
        bidder.start();
        canceller.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        bidder.join(TimeUnit.SECONDS.toMillis(20));
        canceller.join(TimeUnit.SECONDS.toMillis(20));

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        User reloadedSeller = userRepository.findById(sellerId).orElseThrow();

        if (reloaded.getStatus() == AuctionStatus.CANCELLED) {
            // Exactly one of the two orderings:
            //   (a) cancel commits first, bid then sees CANCELLED → rejected.
            //   (b) bid commits first, cancel then runs on ACTIVE-with-bids →
            //       succeeds; the counter bumps and the bid row survives.
            assertThat(cancelSucceeded.get())
                    .as("cancel must have committed when final status=CANCELLED")
                    .isTrue();
            if (bidSucceeded.get()) {
                // (b) — bid won the lock first, cancel followed.
                assertThat(reloaded.getBidCount())
                        .as("bid that committed first must persist bidCount=1")
                        .isEqualTo(1);
                assertThat(bidRepository.findByAuctionIdOrderByCreatedAtAsc(auctionId))
                        .as("bid row must persist when bid commits before cancel")
                        .hasSize(1);
                assertThat(reloadedSeller.getCancelledWithBids())
                        .as("cancelled_with_bids counter must bump when cancel runs on ACTIVE with bids")
                        .isEqualTo(1);
            } else {
                // (a) — cancel won the lock first, bid then failed 409.
                assertThat(bidError.get())
                        .as("bid must have surfaced a deterministic 4xx when cancel committed first; got %s",
                                bidError.get())
                        .satisfiesAnyOf(
                                t -> assertThat(t).isInstanceOf(InvalidAuctionStateException.class),
                                t -> assertThat(t).isInstanceOf(AuctionAlreadyEndedException.class));
                assertThat(reloaded.getBidCount())
                        .as("no bid should have committed when cancel won the lock")
                        .isZero();
                assertThat(bidRepository.findByAuctionIdOrderByCreatedAtAsc(auctionId))
                        .as("no bid row when cancel won the lock")
                        .isEmpty();
                assertThat(reloadedSeller.getCancelledWithBids())
                        .as("cancelled_with_bids must NOT bump when no bids were present")
                        .isZero();
            }
        } else {
            // The auction is still ACTIVE — only possible if the cancel threw
            // (e.g. its own validation tripped) and bid committed cleanly.
            // This is not an expected orderings outcome for this fixture:
            // both operations are legal from ACTIVE, so at least one should
            // have committed. Surface a descriptive failure if it happens.
            throw new AssertionError(
                    "Unexpected final state=" + reloaded.getStatus()
                    + " bidSucceeded=" + bidSucceeded.get()
                    + " cancelSucceeded=" + cancelSucceeded.get()
                    + " bidError=" + bidError.get()
                    + " cancelError=" + cancelError.get());
        }

        // Invariant: currentBid + bidCount on the auction must match the bid
        // rows on disk. The row lock is what guarantees this; a broken
        // retrofit would let the non-locked writer race past the locked one.
        int rows = bidRepository.findByAuctionIdOrderByCreatedAtAsc(auctionId).size();
        assertThat(reloaded.getBidCount())
                .as("auction.bidCount must reflect persisted bid rows")
                .isEqualTo(rows);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InvalidAuctionStateException
                    || cur instanceof AuctionAlreadyEndedException) {
                return cur;
            }
            cur = cur.getCause();
        }
        return t;
    }

    private void setup() {
        User seller = userRepository.save(User.builder()
                .email("bid-cancel-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Bid/Cancel Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .cancelledWithBids(0)
                .build());
        User bidder = userRepository.save(User.builder()
                .email("bid-cancel-bidder-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Bid/Cancel Bidder")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        Parcel parcel = parcelRepository.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .regionName("BidCancelRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MATURE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = auctionRepository.save(Auction.builder()
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .listingFeeAmt(0L)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .startsAt(now.minusHours(1))
                .endsAt(now.plusDays(1))
                .originalEndsAt(now.plusDays(1))
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());

        this.sellerId = seller.getId();
        this.bidderId = bidder.getId();
        this.parcelId = parcel.getId();
        this.auctionId = auction.getId();
    }
}
