package com.slparcelauctions.backend.auction.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckTask;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Epic 04 Task 7 regression: bid placement vs ownership-driven suspension
 * racing on the same auction. The World API client is mocked to return a
 * mismatched owner so the {@link OwnershipCheckTask} invariably routes to
 * {@link com.slparcelauctions.backend.auction.monitoring.SuspensionService#suspendForOwnershipChange}.
 * With {@link AuctionRepository#findByIdForUpdate} retrofitted onto the check
 * entry path, both writers serialise on the auction row lock.
 *
 * <p>Accepted orderings:
 * <ol>
 *   <li>Bid commits first, suspension task takes the lock, observes
 *       status=ACTIVE (bid never flips status) → suspends; auction ends up
 *       SUSPENDED with the bid row persisted, currentBid reflects the
 *       winning amount, and a fraud flag was written.</li>
 *   <li>Suspension commits first, bid takes the lock, observes
 *       status=SUSPENDED → rejects with
 *       {@link InvalidAuctionStateException}.</li>
 * </ol>
 *
 * <p>Only one bid row is ever persisted; {@code bidCount} on the auction
 * always matches the bid rows on disk. A regression that reverted the
 * ownership path to non-locking {@code findById} would let the suspension
 * and bid writers step on each other's reads of {@code currentBid}.
 *
 * <p><strong>Critical:</strong> this class is NOT {@code @Transactional}.
 * The bid thread drives its own {@link TransactionTemplate}; the ownership
 * check runs on Spring's async executor (separate thread, fresh transaction)
 * because {@link OwnershipCheckTask#checkOne} is {@code @Async}. Awaitility
 * polls the committed state to observe the async outcome.
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
class BidSuspendRaceTest {

    @Autowired BidService bidService;
    @Autowired OwnershipCheckTask ownershipCheckTask;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired FraudFlagRepository fraudFlagRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired javax.sql.DataSource dataSource;

    // Stub the World API so the ownership check resolves deterministically to
    // an owner-mismatch, forcing the suspension branch.
    @MockitoBean SlWorldApiClient worldApi;

    // Stub the STOMP publisher so BidService's afterCommit callback does not
    // trip on a missing broker under the concurrency harness.
    @MockitoBean AuctionBroadcastPublisher publisher;

    private Long auctionId;
    private Long sellerId;
    private Long bidderId;
    private Long parcelId;
    private UUID parcelUuid;

    @AfterEach
    void cleanup() throws Exception {
        // Raw JDBC so cascade ordering is deterministic regardless of which
        // writer won the race (fraud_flags rows land on the suspension path
        // and must be deleted before the auction row).
        if (auctionId != null) {
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (var stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM fraud_flags WHERE auction_id = " + auctionId);
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
        parcelUuid = null;
    }

    @Test
    void concurrentBidAndOwnershipSuspend_serialisesOnRowLock() throws Exception {
        setup();

        // World API always reports a different owner (someone "hijacked" the
        // parcel), so the check invariably dispatches suspension.
        UUID attacker = UUID.randomUUID();
        when(worldApi.fetchParcel(any(UUID.class)))
                .thenReturn(Mono.just(new ParcelMetadata(
                        parcelUuid, attacker, "agent",
                        "Hijacked", "SuspendRaceRegion",
                        1024, "desc", "http://example.com/snap.jpg", "MODERATE",
                        128.0, 64.0, 22.0)));

        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> bidError = new AtomicReference<>();
        AtomicReference<Boolean> bidSucceeded = new AtomicReference<>(false);

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

        Runnable suspendAttempt = () -> {
            ready.countDown();
            try {
                go.await();
                // @Async dispatches to Spring's executor; the call returns
                // immediately and the actual check runs on another thread.
                ownershipCheckTask.checkOne(auctionId);
            } catch (Throwable t) {
                // The proxy returns immediately so this branch is unlikely to
                // surface anything — the async work's exceptions go to the
                // executor's uncaught-exception handler. Log only.
            }
        };

        Thread bidder = new Thread(bidAttempt, "race-bidder");
        Thread suspender = new Thread(suspendAttempt, "race-suspender");
        bidder.setDaemon(true);
        suspender.setDaemon(true);
        bidder.start();
        suspender.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        bidder.join(TimeUnit.SECONDS.toMillis(20));
        suspender.join(TimeUnit.SECONDS.toMillis(20));

        // The async suspension must have committed within the poll window —
        // either before OR after the bid. Both orderings land on a
        // deterministic final state.
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();
            assertThat(reloaded.getStatus())
                    .as("auction must be SUSPENDED after the ownership check commits")
                    .isEqualTo(AuctionStatus.SUSPENDED);
            List<FraudFlag> flags = fraudFlagRepository.findByAuctionId(auctionId);
            assertThat(flags)
                    .as("fraud flag must be recorded alongside the suspension")
                    .isNotEmpty();
        });

        Auction reloaded = auctionRepository.findById(auctionId).orElseThrow();

        if (bidSucceeded.get()) {
            // (1) Bid committed first; suspension then ran on ACTIVE, flipped
            // to SUSPENDED, wrote a fraud flag.
            assertThat(reloaded.getBidCount())
                    .as("bid that committed before the suspension must persist")
                    .isEqualTo(1);
            assertThat(reloaded.getCurrentBid())
                    .as("currentBid must reflect the committed bid amount")
                    .isEqualTo(1500L);
            assertThat(bidRepository.findByAuctionIdOrderByCreatedAtAsc(auctionId))
                    .as("bid row must persist across the suspension")
                    .hasSize(1);
        } else {
            // (2) Suspension committed first; bid took the lock after, saw
            // status=SUSPENDED, failed 409.
            assertThat(bidError.get())
                    .as("bid must fail deterministically when suspension commits first; got %s",
                            bidError.get())
                    .satisfiesAnyOf(
                            t -> assertThat(t).isInstanceOf(InvalidAuctionStateException.class),
                            t -> assertThat(t).isInstanceOf(AuctionAlreadyEndedException.class));
            assertThat(reloaded.getBidCount())
                    .as("no bid should have committed when suspension won the lock")
                    .isZero();
            assertThat(bidRepository.findByAuctionIdOrderByCreatedAtAsc(auctionId))
                    .as("no bid row when suspension won the lock")
                    .isEmpty();
        }

        // Invariant across both orderings: auction.bidCount matches the bid
        // rows on disk — a broken retrofit would break this.
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
                .email("suspend-race-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Suspend Race Seller")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        User bidder = userRepository.save(User.builder()
                .email("suspend-race-bidder-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Suspend Race Bidder")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
        UUID pUuid = UUID.randomUUID();
        Parcel parcel = parcelRepository.save(Parcel.builder()
                .slParcelUuid(pUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .regionName("SuspendRaceRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
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
        this.parcelUuid = pUuid;
        this.auctionId = auction.getId();
    }
}
