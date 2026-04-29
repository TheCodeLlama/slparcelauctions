package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Verifies {@link AuctionRepository#findByIdForUpdate(Long)} really takes a
 * row-level write lock.
 *
 * <p>We open a transaction on one thread, call {@code findByIdForUpdate}, then
 * from the main thread query {@code pg_locks} on a third connection and assert
 * the lock entry is visible. This exercises the DB behaviour without relying
 * on lock-timeout coercion, which is finicky cross-driver.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AuctionRepositoryLockTest {

    @Autowired AuctionRepository auctionRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    /**
     * Counts the pids — other than the caller's own backend pid — that hold a
     * {@code RowShareLock} on the {@code auctions} relation. {@code SELECT ...
     * FOR UPDATE} takes that relation-level lock in addition to tagging the
     * row via its {@code xmax} (Postgres doesn't usually surface an
     * uncontended tuple lock in {@code pg_locks}, so we can't filter on
     * {@code locktype='tuple'}). Excluding {@code pg_backend_pid()} removes
     * this assertion query's own session; baselining before vs after the
     * holder thread grabs its lock makes the assertion robust to background
     * beans (ownership-monitor scheduler, verification sweeps) that may also
     * touch the {@code auctions} table in parallel.
     */
    private long countHolderSessionRowShareLocks() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_locks "
                        + "WHERE relation = 'auctions'::regclass "
                        + "AND mode = 'RowShareLock' "
                        + "AND pid <> pg_backend_pid() "
                        + "AND granted = true",
                Long.class);
        return count == null ? 0L : count;
    }

    @Test
    void findByIdForUpdate_holdsRowLockUntilCommit() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);


        User seller = userRepository.save(User.builder()
                .email("lock-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Lock Seller")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());

        Parcel parcel = parcelRepository.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .regionName("LockTestRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());

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
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());

        Long auctionId = auction.getId();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> threadError = new AtomicReference<>();

        // Snapshot the lock count before the holder thread enters its tx, so
        // we can assert a delta rather than an absolute count. Background
        // beans (ownership-monitor scheduler, verification sweeps) may be
        // holding their own RowShareLocks on auctions during this test.
        long baselineLocks = countHolderSessionRowShareLocks();

        Thread holder = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    Optional<Auction> locked = auctionRepository.findByIdForUpdate(auctionId);
                    assertThat(locked).isPresent();
                    lockAcquired.countDown();
                    try {
                        // Hold the transaction open while the main thread
                        // checks pg_locks on a separate connection.
                        boolean released = release.await(5, TimeUnit.SECONDS);
                        if (!released) {
                            throw new IllegalStateException(
                                    "Main thread never released the latch");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            } catch (Throwable t) {
                threadError.set(t);
                lockAcquired.countDown();
                release.countDown();
            }
        }, "findByIdForUpdate-holder");
        holder.setDaemon(true);
        holder.start();

        try {
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadError.get()).isNull();

            // Assert a delta: once the holder thread has taken its SELECT ...
            // FOR UPDATE, at least one additional non-self session should be
            // holding RowShareLock on the auctions table compared to the
            // baseline we captured before the holder started. Asserting a
            // delta (rather than an absolute count) immunises the test
            // against background beans — ownership-monitor scheduler,
            // verification sweeps — that may independently hold
            // RowShareLocks on auctions in parallel, while still catching
            // the case where findByIdForUpdate quietly stopped taking a
            // row-level lock at all.
            long heldLocks = countHolderSessionRowShareLocks();
            assertThat(heldLocks - baselineLocks)
                    .as("findByIdForUpdate should add at least one RowShareLock "
                            + "on auctions from a non-self session "
                            + "(baseline=%d, observed=%d)", baselineLocks, heldLocks)
                    .isGreaterThanOrEqualTo(1L);
        } finally {
            release.countDown();
            holder.join(5_000);
            // This test is NOT @Transactional (we need the auction row
            // committed so the holder thread's SELECT FOR UPDATE finds it),
            // so clean up the rows we committed or they'll pollute other
            // repository tests — e.g. findDueForOwnershipCheck which picks up
            // any ACTIVE auction with null lastOwnershipCheckAt.
            auctionRepository.deleteById(auctionId);
            parcelRepository.delete(parcel);
            userRepository.delete(seller);
        }

        assertThat(threadError.get()).isNull();
    }
}
