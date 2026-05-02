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
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.saved.exception.SavedLimitReachedException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

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
    @Autowired ParcelRepository parcelRepo;
    @Autowired JdbcTemplate jdbc;

    private User user;
    private final List<Long> seededAuctionIds = new ArrayList<>();
    private final List<Long> seededParcelIds = new ArrayList<>();
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

        // 499 ACTIVE auctions, then bulk insert their saves.
        OffsetDateTime now = OffsetDateTime.now();
        List<Object[]> savedRows = new ArrayList<>(499);
        for (int i = 0; i < 499; i++) {
            Auction a = seedActive(i);
            savedRows.add(new Object[] {
                    user.getId(),
                    a.getId(),
                    Timestamp.from(now.minusSeconds(499 - i).toInstant())
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
            jdbc.update("DELETE FROM saved_auctions WHERE user_id = ?", seededUserId);
        }
        for (Long aid : seededAuctionIds) {
            try {
                auctionRepo.deleteById(aid);
            } catch (Exception ignored) {
                // best-effort
            }
        }
        for (Long pid : seededParcelIds) {
            try {
                parcelRepo.deleteById(pid);
            } catch (Exception ignored) {
                // best-effort
            }
        }
        if (seededUserId != null) {
            try {
                userRepo.deleteById(seededUserId);
            } catch (Exception ignored) {
                // best-effort
            }
        }
        seededAuctionIds.clear();
        seededParcelIds.clear();
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
                    service.save(seededUserId, a1.getId());
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
                    service.save(seededUserId, a2.getId());
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
        Parcel p = parcelRepo.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                                .areaSqm(1024)
                                .verified(true)
                .build());
        seededParcelIds.add(p.getId());

        OffsetDateTime now = OffsetDateTime.now();
        Auction a = Auction.builder()
                .parcel(p)
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
        seededAuctionIds.add(saved.getId());
        return saved;
    }

}
