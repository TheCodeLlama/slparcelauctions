package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for {@link NotificationPublisherImpl}.
 *
 * <p>Uses a real Postgres connection (dev profile) to verify that each
 * publisher method persists a row with the expected category, recipient,
 * coalesce key, and data shape, and that {@link NotificationWsBroadcasterPort}
 * is called after-commit (not during the parent transaction).
 *
 * <p>The fan-out tests additionally verify: each bidder gets its own row
 * (not shared), the batch only runs on commit (not on rollback), and a
 * single FK violation does not abort delivery to the remaining recipients.
 *
 * <p>All assertions use {@link NotificationRepository#findAllByUserId} rather
 * than {@code findAll()} so the tests are isolated from notifications left
 * in the shared dev database by other test classes.
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
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
class NotificationPublisherImplTest {

    @Autowired NotificationPublisher publisher;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    @MockitoBean
    NotificationWsBroadcasterPort broadcasterPort;

    /** Default-propagation template — satisfies MANDATORY from NotificationService.publish. */
    private TransactionTemplate transactionTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        transactionTemplate = new TransactionTemplate(txManager);
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                for (Long id : userIds) {
                    if (id != null) {
                        stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                        stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        stmt.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userIds.clear();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User testUser(String prefix) {
        User u = userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build());
        userIds.add(u.getId());
        return u;
    }

    /** Returns notifications for the given user, scoped to avoid cross-test contamination. */
    private List<Notification> notifFor(Long userId) {
        return repo.findAllByUserId(userId);
    }

    // ── Bidding ───────────────────────────────────────────────────────────────

    @Test
    void outbid_notProxy_publishesNotification() {
        User alice = testUser("outbid-alice");

        transactionTemplate.execute(status -> {
            publisher.outbid(alice.getId(), 42L, "Hampton Hills", 5200L, false,
                    OffsetDateTime.now().plusMinutes(8));
            return null;
        });

        List<Notification> rows = notifFor(alice.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getUser().getId()).isEqualTo(alice.getId());
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.OUTBID);
        assertThat(n.getCoalesceKey()).isEqualTo("outbid:" + alice.getId() + ":42");
        Map<String, Object> data = n.getData();
        assertThat(data).containsKeys("auctionId", "parcelName", "currentBidL", "isProxyOutbid", "endsAt");
        assertThat(data).containsEntry("isProxyOutbid", false);

        verify(broadcasterPort).broadcastUpsert(eq(alice.getId()), any(), any());
    }

    @Test
    void outbid_proxy_setsIsProxyOutbidTrue() {
        User alice = testUser("outbid-proxy-alice");

        transactionTemplate.execute(status -> {
            publisher.outbid(alice.getId(), 43L, "Pinewood Flats", 6000L, true,
                    OffsetDateTime.now().plusMinutes(5));
            return null;
        });

        List<Notification> rows = notifFor(alice.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getData()).containsEntry("isProxyOutbid", true);
        assertThat(n.getCoalesceKey()).isEqualTo("outbid:" + alice.getId() + ":43");

        verify(broadcasterPort).broadcastUpsert(eq(alice.getId()), any(), any());
    }

    @Test
    void proxyExhausted_publishesWithCorrectCoalesceKey() {
        User bob = testUser("proxy-bob");

        transactionTemplate.execute(status -> {
            publisher.proxyExhausted(bob.getId(), 55L, "Eastwood Plot", 9000L,
                    OffsetDateTime.now().plusMinutes(15));
            return null;
        });

        List<Notification> rows = notifFor(bob.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.PROXY_EXHAUSTED);
        assertThat(n.getCoalesceKey()).isEqualTo("proxy_exhausted:" + bob.getId() + ":55");
        assertThat(n.getData()).containsKeys("auctionId", "parcelName", "proxyMaxL", "endsAt");

        verify(broadcasterPort).broadcastUpsert(eq(bob.getId()), any(), any());
    }

    // ── Auction result ────────────────────────────────────────────────────────

    @Test
    void auctionWon_publishesNullCoalesceKey() {
        User alice = testUser("won-alice");

        transactionTemplate.execute(status -> {
            publisher.auctionWon(alice.getId(), 10L, "Linden Meadows", 20000L);
            return null;
        });

        List<Notification> rows = notifFor(alice.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.AUCTION_WON);
        assertThat(n.getCoalesceKey()).isNull();
        assertThat(n.getData()).containsKeys("auctionId", "parcelName", "winningBidL");

        verify(broadcasterPort).broadcastUpsert(eq(alice.getId()), any(), any());
    }

    @Test
    void auctionLost_publishesNullCoalesceKey() {
        User bob = testUser("lost-bob");

        transactionTemplate.execute(status -> {
            publisher.auctionLost(bob.getId(), 11L, "Linden Meadows", 20000L);
            return null;
        });

        List<Notification> rows = notifFor(bob.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.AUCTION_LOST);
        assertThat(n.getCoalesceKey()).isNull();

        verify(broadcasterPort).broadcastUpsert(eq(bob.getId()), any(), any());
    }

    @Test
    void auctionEndedSold_publishesSellerRow() {
        User seller = testUser("sold-seller");

        transactionTemplate.execute(status -> {
            publisher.auctionEndedSold(seller.getId(), 12L, "Bayfront Block", 15000L);
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.AUCTION_ENDED_SOLD);
        assertThat(n.getData()).containsKey("winningBidL");
        assertThat(((Number) n.getData().get("winningBidL")).longValue()).isEqualTo(15000L);

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void auctionEndedReserveNotMet_publishesHighestBid() {
        User seller = testUser("reserve-seller");

        transactionTemplate.execute(status -> {
            publisher.auctionEndedReserveNotMet(seller.getId(), 13L, "Hillside Lot", 5000L);
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.AUCTION_ENDED_RESERVE_NOT_MET);
        assertThat(n.getData()).containsKeys("auctionId", "parcelName", "highestBidL");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void auctionEndedNoBids_publishesMinimalData() {
        User seller = testUser("nobids-seller");

        transactionTemplate.execute(status -> {
            publisher.auctionEndedNoBids(seller.getId(), 14L, "Empty Plot");
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.AUCTION_ENDED_NO_BIDS);
        assertThat(n.getData()).containsKeys("auctionId", "parcelName");
        assertThat(n.getCoalesceKey()).isNull();

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void auctionEndedBoughtNow_publishesBuyNowAmount() {
        User seller = testUser("buynow-seller");

        transactionTemplate.execute(status -> {
            publisher.auctionEndedBoughtNow(seller.getId(), 15L, "Prime Corner", 30000L);
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.AUCTION_ENDED_BOUGHT_NOW);
        assertThat(n.getData()).containsKey("buyNowL");
        assertThat(((Number) n.getData().get("buyNowL")).longValue()).isEqualTo(30000L);

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    // ── Escrow ────────────────────────────────────────────────────────────────

    @Test
    void escrowFunded_publishesTransferDeadline() {
        User seller = testUser("escrow-funded-seller");

        transactionTemplate.execute(status -> {
            publisher.escrowFunded(seller.getId(), 20L, 200L, "Parcel A",
                    OffsetDateTime.now().plusHours(72));
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_FUNDED);
        assertThat(n.getCoalesceKey()).isNull();
        assertThat(n.getData()).containsKeys("auctionId", "escrowId", "parcelName", "transferDeadline");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void escrowTransferConfirmed_publishesRow() {
        User seller = testUser("escrow-confirmed-seller");

        transactionTemplate.execute(status -> {
            publisher.escrowTransferConfirmed(seller.getId(), 21L, 201L, "Parcel B");
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_TRANSFER_CONFIRMED);
        assertThat(n.getData()).containsKeys("auctionId", "escrowId", "parcelName");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void escrowPayout_publishesPayoutAmount() {
        User seller = testUser("payout-seller");

        transactionTemplate.execute(status -> {
            publisher.escrowPayout(seller.getId(), 22L, 202L, "Parcel C", 18500L);
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_PAYOUT);
        assertThat(n.getData()).containsKey("payoutL");
        assertThat(((Number) n.getData().get("payoutL")).longValue()).isEqualTo(18500L);

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void escrowExpired_publishesRow() {
        User buyer = testUser("escrow-expired-buyer");

        transactionTemplate.execute(status -> {
            publisher.escrowExpired(buyer.getId(), 23L, 203L, "Parcel D");
            return null;
        });

        List<Notification> rows = notifFor(buyer.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_EXPIRED);
        assertThat(n.getData()).containsKeys("auctionId", "escrowId", "parcelName");

        verify(broadcasterPort).broadcastUpsert(eq(buyer.getId()), any(), any());
    }

    @Test
    void escrowDisputed_publishesReasonCategory() {
        User user = testUser("disputed-user");

        transactionTemplate.execute(status -> {
            publisher.escrowDisputed(user.getId(), 24L, 204L, "Parcel E", "SELLER_NO_TRANSFER");
            return null;
        });

        List<Notification> rows = notifFor(user.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_DISPUTED);
        assertThat(n.getData()).containsEntry("reasonCategory", "SELLER_NO_TRANSFER");

        verify(broadcasterPort).broadcastUpsert(eq(user.getId()), any(), any());
    }

    @Test
    void escrowFrozen_publishesReason() {
        User user = testUser("frozen-user");

        transactionTemplate.execute(status -> {
            publisher.escrowFrozen(user.getId(), 25L, 205L, "Parcel F", "Suspected fraud");
            return null;
        });

        List<Notification> rows = notifFor(user.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_FROZEN);
        assertThat(n.getData()).containsEntry("reason", "Suspected fraud");

        verify(broadcasterPort).broadcastUpsert(eq(user.getId()), any(), any());
    }

    @Test
    void escrowPayoutStalled_publishesRow() {
        User seller = testUser("stalled-seller");

        transactionTemplate.execute(status -> {
            publisher.escrowPayoutStalled(seller.getId(), 26L, 206L, "Parcel G");
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_PAYOUT_STALLED);
        assertThat(n.getData()).containsKeys("auctionId", "escrowId", "parcelName");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void escrowTransferReminder_publishesWithCorrectCoalesceKey() {
        User seller = testUser("reminder-seller");

        transactionTemplate.execute(status -> {
            publisher.escrowTransferReminder(seller.getId(), 27L, 207L, "Parcel H",
                    OffsetDateTime.now().plusHours(12));
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.ESCROW_TRANSFER_REMINDER);
        assertThat(n.getCoalesceKey()).isEqualTo("transfer_reminder:" + seller.getId() + ":207");
        assertThat(n.getData()).containsKeys("auctionId", "escrowId", "parcelName", "transferDeadline");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    // ── Listing status ────────────────────────────────────────────────────────

    @Test
    void listingVerified_publishesRow() {
        User seller = testUser("verified-seller");

        transactionTemplate.execute(status -> {
            publisher.listingVerified(seller.getId(), 30L, "Sunrise Acres");
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.LISTING_VERIFIED);
        assertThat(n.getCoalesceKey()).isNull();
        assertThat(n.getData()).containsKeys("auctionId", "parcelName");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void listingSuspended_publishesReason() {
        User seller = testUser("suspended-seller");

        transactionTemplate.execute(status -> {
            publisher.listingSuspended(seller.getId(), 31L, "Sunset Strip", "Ownership verification failed");
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.LISTING_SUSPENDED);
        assertThat(n.getData()).containsEntry("reason", "Ownership verification failed");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    @Test
    void listingReviewRequired_publishesReason() {
        User seller = testUser("review-required-seller");

        transactionTemplate.execute(status -> {
            publisher.listingReviewRequired(seller.getId(), 32L, "Mapleview", "Disputed boundary");
            return null;
        });

        List<Notification> rows = notifFor(seller.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.LISTING_REVIEW_REQUIRED);
        assertThat(n.getData()).containsEntry("reason", "Disputed boundary");

        verify(broadcasterPort).broadcastUpsert(eq(seller.getId()), any(), any());
    }

    // ── Reviews ───────────────────────────────────────────────────────────────

    @Test
    void reviewReceived_publishesReviewIdAndRating() {
        User reviewee = testUser("reviewee-user");

        transactionTemplate.execute(status -> {
            publisher.reviewReceived(reviewee.getId(), 88L, 40L, "Riverside Lot", 4);
            return null;
        });

        List<Notification> rows = notifFor(reviewee.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.REVIEW_RECEIVED);
        assertThat(n.getCoalesceKey()).isNull();
        assertThat(n.getData()).containsKeys("reviewId", "rating", "auctionId", "parcelName");

        verify(broadcasterPort).broadcastUpsert(eq(reviewee.getId()), any(), any());
    }

    // ── Fan-out ───────────────────────────────────────────────────────────────

    @Test
    void listingCancelledBySellerFanout_eachRecipientGetsOwnRow() {
        User a = testUser("fanout-a");
        User b = testUser("fanout-b");
        User c = testUser("fanout-c");

        transactionTemplate.execute(status -> {
            publisher.listingCancelledBySellerFanout(42L,
                    List.of(a.getId(), b.getId(), c.getId()),
                    "Hampton Hills", "ownership lost");
            return null;
        });

        // Each user should have exactly one LISTING_CANCELLED_BY_SELLER row.
        assertThat(notifFor(a.getId())).hasSize(1);
        assertThat(notifFor(b.getId())).hasSize(1);
        assertThat(notifFor(c.getId())).hasSize(1);
        assertThat(notifFor(a.getId()).get(0).getCategory())
                .isEqualTo(NotificationCategory.LISTING_CANCELLED_BY_SELLER);
        assertThat(notifFor(b.getId()).get(0).getCategory())
                .isEqualTo(NotificationCategory.LISTING_CANCELLED_BY_SELLER);
        assertThat(notifFor(c.getId()).get(0).getCategory())
                .isEqualTo(NotificationCategory.LISTING_CANCELLED_BY_SELLER);

        verify(broadcasterPort, times(3)).broadcastUpsert(anyLong(), any(), any());
    }

    @Test
    void fanout_runsInAfterCommitNotInParentTx() {
        User a = testUser("fanout-rollback-a");

        try {
            transactionTemplate.execute(status -> {
                publisher.listingCancelledBySellerFanout(42L, List.of(a.getId()),
                        "Hampton Hills", "test rollback");
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception ignored) {}

        // Parent rolled back — fan-out NEVER fired.
        assertThat(notifFor(a.getId())).isEmpty();
        verify(broadcasterPort, never()).broadcastUpsert(anyLong(), any(), any());
    }

    @Test
    void fanout_partialFailureDoesNotBlockRemainingRecipients() {
        User a = testUser("fanout-partial-a");
        long staleBidderId = 999_999L; // doesn't exist — FK violation
        User c = testUser("fanout-partial-c");

        transactionTemplate.execute(status -> {
            publisher.listingCancelledBySellerFanout(42L,
                    List.of(a.getId(), staleBidderId, c.getId()),
                    "Hampton Hills", "test");
            return null;
        });

        // Two valid recipients should land; one stale fails silently.
        assertThat(notifFor(a.getId())).hasSize(1);
        assertThat(notifFor(c.getId())).hasSize(1);
        assertThat(notifFor(a.getId()).get(0).getCategory())
                .isEqualTo(NotificationCategory.LISTING_CANCELLED_BY_SELLER);
        assertThat(notifFor(c.getId()).get(0).getCategory())
                .isEqualTo(NotificationCategory.LISTING_CANCELLED_BY_SELLER);
        verify(broadcasterPort, times(2)).broadcastUpsert(anyLong(), any(), any());
    }
}
