package com.slparcelauctions.backend.auction.saved;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.saved.exception.SavedLimitReachedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Verifies the {@code pg_advisory_xact_lock} cap-enforcement contract — two
 * threads racing to save when the count is 499 yield exactly one success and
 * one {@link SavedLimitReachedException}, with the final {@code saved_auctions}
 * row count clamped at {@value SavedAuctionService#SAVED_CAP}.
 *
 * <p>Pre-fill uses bulk JDBC insert (~30ms total) rather than 499 service
 * calls (~10-30s). Each row needs a fresh ACTIVE auction since the unique
 * constraint is {@code (user_id, auction_id)}; auctions are inserted via
 * the JPA repository in a tight loop.
 *
 * <p>Not {@code @Transactional} — the test relies on real commits to
 * exercise the advisory lock + unique constraint paths.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class SavedAuctionConcurrencyTest {

    @Autowired SavedAuctionService service;
    @Autowired SavedAuctionRepository savedRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired JdbcTemplate jdbc;

    private User user;
    private final List<Long> seededAuctionIds = new ArrayList<>();
    private Long seededUserId;

    @BeforeEach
    void seed() {
        user = userRepo.save(User.builder()
                .email("cap-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Capper")
                .verified(true)
                .build());
        seededUserId = user.getId();

        // Seed 499 ACTIVE auctions via a single bulk JDBC insert. The previous
        // pattern called `auctionRepo.save(...)` 499×2 in a tight loop, each
        // grabbing+releasing a Hikari connection. Background scheduler threads
        // in the boot context routinely starve the pool when this runs late
        // in the surefire fork, causing seed to time out at 30s+ per save and
        // the whole test to wall-clock past 13 minutes. A single COPY-style
        // batch holds one connection for ~30ms total. No snapshot rows —
        // SavedAuctionService.save doesn't reach for the snapshot.
        OffsetDateTime now = OffsetDateTime.now();
        Timestamp createdAt = Timestamp.from(now.toInstant());
        List<Object[]> auctionRows = new ArrayList<>(499);
        for (int i = 0; i < 499; i++) {
            auctionRows.add(new Object[] {
                    UUID.randomUUID(),                             // public_id
                    UUID.randomUUID(),                             // sl_parcel_uuid
                    user.getId(),                                  // seller_id
                    AuctionStatus.ACTIVE.name(),                   // status
                    VerificationTier.BOT.name(),                   // verification_tier
                    "T-" + i,                                      // title
                    1L,                                            // starting_bid
                    1L,                                            // current_bid
                    168,                                           // duration_hours
                    Timestamp.from(now.minusHours(1).toInstant()), // starts_at
                    Timestamp.from(now.plusDays(7).toInstant()),   // ends_at
                    Timestamp.from(now.plusDays(7).toInstant()),   // original_ends_at
                    new BigDecimal("0.05"),                        // commission_rate
                    BigDecimal.ZERO,                               // agent_fee_rate
                    createdAt                                      // created_at
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO auctions (public_id, sl_parcel_uuid, seller_id, status, "
                        + "verification_tier, title, starting_bid, current_bid, duration_hours, "
                        + "starts_at, ends_at, original_ends_at, commission_rate, agent_fee_rate, "
                        + "created_at, listing_fee_paid, snipe_protect) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, false)",
                auctionRows);

        // Pull the IDs of the just-inserted auctions for the saved_auctions
        // batch + per-test cleanup. They're contiguous in serial order but
        // we read them by seller_id to be exact.
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM auctions WHERE seller_id = ? ORDER BY id ASC",
                Long.class, user.getId());
        seededAuctionIds.addAll(ids);

        // Bulk insert 499 saved_auctions rows.
        List<Object[]> savedRows = new ArrayList<>(499);
        for (int i = 0; i < ids.size(); i++) {
            savedRows.add(new Object[] {
                    user.getId(),
                    ids.get(i),
                    Timestamp.from(now.minusSeconds(ids.size() - i).toInstant())
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO saved_auctions (user_id, auction_id, saved_at) VALUES (?, ?, ?)",
                savedRows);

        assertThat(savedRepo.countByUserId(user.getId())).isEqualTo(499);
    }

    @AfterEach
    void cleanup() {
        if (seededUserId != null) {
            // Bulk DELETEs in one connection — same rationale as the seed
            // bulk insert. Snapshots only exist for the 2 race auctions
            // (seedActive); the 499 bulk-seeded auctions have none, so the
            // snapshot delete is a no-op for those rows.
            jdbc.update("DELETE FROM saved_auctions WHERE user_id = ?", seededUserId);
            jdbc.update("DELETE FROM auction_parcel_snapshots "
                    + "WHERE auction_id IN (SELECT id FROM auctions WHERE seller_id = ?)",
                    seededUserId);
            jdbc.update("DELETE FROM auctions WHERE seller_id = ?", seededUserId);
            try {
                userRepo.deleteById(seededUserId);
            } catch (Exception ignored) {
                // best-effort
            }
        }
        seededAuctionIds.clear();
        seededUserId = null;
    }

    @Test
    void advisoryLock_preventsCapBreach_whenTwoThreadsRaceAt499() throws Exception {
        // Two concurrent saves from count = 499. Exactly one must win; the
        // other must hit SavedLimitReachedException. Final count = 500.
        Auction a1 = seedActive(900);
        Auction a2 = seedActive(901);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger limitFailures = new AtomicInteger();
            AtomicInteger otherFailures = new AtomicInteger();

            Runnable task1 = () -> {
                try {
                    start.await();
                    service.save(seededUserId, a1.getPublicId());
                    successes.incrementAndGet();
                } catch (SavedLimitReachedException e) {
                    limitFailures.incrementAndGet();
                } catch (Exception e) {
                    otherFailures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            };
            Runnable task2 = () -> {
                try {
                    start.await();
                    service.save(seededUserId, a2.getPublicId());
                    successes.incrementAndGet();
                } catch (SavedLimitReachedException e) {
                    limitFailures.incrementAndGet();
                } catch (Exception e) {
                    otherFailures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            };

            pool.submit(task1);
            pool.submit(task2);
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS))
                    .as("both tasks completed within 20s")
                    .isTrue();

            assertThat(successes.get())
                    .as("exactly one save succeeds when racing at the cap")
                    .isEqualTo(1);
            assertThat(limitFailures.get())
                    .as("the loser hits SavedLimitReachedException")
                    .isEqualTo(1);
            assertThat(otherFailures.get())
                    .as("no unrelated exceptions")
                    .isZero();
            assertThat(savedRepo.countByUserId(seededUserId))
                    .as("final saved count is clamped at the cap")
                    .isEqualTo(SavedAuctionService.SAVED_CAP);
        } finally {
            pool.shutdownNow();
        }
    }

    private Auction seedActive(int idx) {
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = Auction.builder()
                .slParcelUuid(parcelUuid)
                .seller(user)
                .title("T-" + idx)
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.BOT)
                .startingBid(1L)
                .currentBid(1L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        a.setStartsAt(now.minusHours(1));
        a.setEndsAt(now.plusDays(7));
        a.setOriginalEndsAt(now.plusDays(7));
        Auction saved = auctionRepo.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(user.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Test Parcel " + idx)
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        saved = auctionRepo.save(saved);
        seededAuctionIds.add(saved.getId());
        return saved;
    }

}
