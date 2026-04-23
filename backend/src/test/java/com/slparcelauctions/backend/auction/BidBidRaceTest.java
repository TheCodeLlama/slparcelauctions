package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.exception.BidTooLowException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Success Criterion §1 regression test: two concurrent {@code placeBid}
 * calls for the same auction, each proposing the starting bid. Exactly one
 * must commit; the other must observe the committed {@code currentBid}
 * after the lock releases and trip
 * {@link BidTooLowException}.
 *
 * <p><strong>Critical:</strong> this class is NOT {@code @Transactional}.
 * The test framework's ambient transaction would serialise the two threads
 * and mask the race. Each thread drives its own
 * {@link TransactionTemplate}; the fixture rows are committed in
 * {@link #setup} and cleaned up in {@link #cleanup}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class BidBidRaceTest {

    @Autowired BidService bidService;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;

    // Task 2 runs without a real STOMP broker — swap the publisher for a
    // mock so the afterCommit callback doesn't blow up under concurrent
    // load with no broker registered.
    @MockitoBean AuctionBroadcastPublisher publisher;

    private Long auctionId;
    private Long sellerId;
    private Long bidderAId;
    private Long bidderBId;
    private Long parcelId;

    @AfterEach
    void cleanup() {
        // Delete committed fixture rows inside an explicit TransactionTemplate so
        // each DELETE is atomic and visible to the next test class. Without this
        // hook the race auction survives across class boundaries and poisons
        // {@code AuctionRepositoryOwnershipCheckTest}, which enumerates every
        // ACTIVE auction with a stale / null lastOwnershipCheckAt.
        TransactionTemplate cleanup = new TransactionTemplate(txManager);
        cleanup.executeWithoutResult(status -> {
            if (auctionId != null) {
                auctionRepository.findById(auctionId).ifPresent(a -> {
                    bidRepository.findByAuctionIdOrderByCreatedAtAsc(a.getId())
                            .forEach(bidRepository::delete);
                    auctionRepository.delete(a);
                });
            }
            if (parcelId != null) {
                parcelRepository.findById(parcelId).ifPresent(parcelRepository::delete);
            }
            if (bidderAId != null) {
                userRepository.findById(bidderAId).ifPresent(userRepository::delete);
            }
            if (bidderBId != null) {
                userRepository.findById(bidderBId).ifPresent(userRepository::delete);
            }
            if (sellerId != null) {
                userRepository.findById(sellerId).ifPresent(userRepository::delete);
            }
        });
        auctionId = null;
        parcelId = null;
        bidderAId = null;
        bidderBId = null;
        sellerId = null;
    }

    @Test
    void concurrentBidsOnSameAuction_exactlyOneSucceeds() throws Exception {
        setup();

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        AtomicInteger successes = new AtomicInteger();

        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await();
                txTemplate.execute(status -> {
                    // Each thread uses its own bidder id so the seller-check
                    // trivially passes and the only race is on currentBid.
                    Long uid = Thread.currentThread().getName().endsWith("-A")
                            ? bidderAId
                            : bidderBId;
                    bidService.placeBid(auctionId, uid, 1000L, "1.2.3.4");
                    successes.incrementAndGet();
                    return null;
                });
            } catch (Throwable t) {
                if (Thread.currentThread().getName().endsWith("-A")) {
                    errorA.set(unwrap(t));
                } else {
                    errorB.set(unwrap(t));
                }
            }
        };

        Thread a = new Thread(attempt, "bid-race-A");
        Thread b = new Thread(attempt, "bid-race-B");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        a.join(TimeUnit.SECONDS.toMillis(15));
        b.join(TimeUnit.SECONDS.toMillis(15));

        // Exactly one thread committed.
        assertThat(successes.get())
                .as("exactly one thread committed; successes=%d, errorA=%s, errorB=%s",
                        successes.get(), errorA.get(), errorB.get())
                .isEqualTo(1);

        // Exactly one thread was rejected with BidTooLowException — the
        // loser observed the winner's committed currentBid when it took
        // the lock.
        long bidTooLow = List.of(errorA, errorB).stream()
                .map(AtomicReference::get)
                .filter(Objects::nonNull)
                .filter(BidTooLowException.class::isInstance)
                .count();
        assertThat(bidTooLow)
                .as("exactly one BidTooLowException; errorA=%s, errorB=%s",
                        errorA.get(), errorB.get())
                .isEqualTo(1L);

        // DB state: exactly one bid row persisted, auction.bidCount=1.
        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(reloaded.getBidCount()).isEqualTo(1);
        assertThat(reloaded.getCurrentBid()).isEqualTo(1000L);
        assertThat(bidRepository.findByAuctionIdOrderByCreatedAtAsc(auctionId)).hasSize(1);
    }

    /**
     * Unwraps {@link org.springframework.transaction.TransactionSystemException}
     * / {@link java.util.concurrent.ExecutionException} wrappers so the test
     * assertion can inspect the service-layer exception type. The wrappers
     * come from {@link TransactionTemplate#execute} surfacing the commit-phase
     * or rollback-phase failure as its own exception type rather than the
     * root cause.
     */
    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof BidTooLowException) return cur;
            cur = cur.getCause();
        }
        return t;
    }

    private void setup() {
        // Seller + two verified bidders + parcel + ACTIVE auction. Saved
        // OUTSIDE the test transaction so both race threads can see them.
        User seller = userRepository.save(User.builder()
                .email("race-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Race Seller")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
        User bidderA = userRepository.save(User.builder()
                .email("race-bidder-a-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Race Bidder A")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
        User bidderB = userRepository.save(User.builder()
                .email("race-bidder-b-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Race Bidder B")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
        Parcel parcel = parcelRepository.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .regionName("RaceTestRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = auctionRepository.save(Auction.builder()
                .title("Test listing")
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
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
        this.bidderAId = bidderA.getId();
        this.bidderBId = bidderB.getId();
        this.parcelId = parcel.getId();
        this.auctionId = auction.getId();
    }
}
