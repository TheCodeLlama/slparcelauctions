package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for {@link NotificationDao#upsert} — the ON CONFLICT
 * coalesce semantics rely on the partial unique index created by
 * {@link NotificationCoalesceIndexInitializer}, so these tests require a
 * real Postgres connection.
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
class NotificationDaoUpsertTest {

    @Autowired NotificationDao dao;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate txTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        User user = userRepository.save(User.builder()
                .email("notif-dao-test-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .build());
        userId = user.getId();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                if (userId != null) {
                    stmt.execute("DELETE FROM notification WHERE user_id = " + userId);
                    stmt.execute("DELETE FROM users WHERE id = " + userId);
                }
            }
        }
        userId = null;
    }

    @Test
    void insertsWhenNoExistingUnreadRow() {
        UpsertResult result = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.OUTBID,
                        "You were outbid", "Someone bid higher", Map.of("auctionId", 1),
                        "outbid:auction:1"));

        assertThat(result).isNotNull();
        assertThat(result.wasUpdate()).isFalse();
        assertThat(result.id()).isPositive();
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();
    }

    @Test
    void updatesWhenUnreadCoalesceKeyMatches() throws InterruptedException {
        UpsertResult first = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.OUTBID,
                        "You were outbid", "Original body", Map.of("bid", 100),
                        "outbid:auction:2"));

        assertThat(first).isNotNull();
        assertThat(first.wasUpdate()).isFalse();

        // Small sleep to ensure updated_at will differ
        Thread.sleep(10);

        UpsertResult second = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.OUTBID,
                        "Still outbid", "Updated body", Map.of("bid", 200),
                        "outbid:auction:2"));

        assertThat(second).isNotNull();
        assertThat(second.wasUpdate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        // createdAt must be preserved from the original insert
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        // updatedAt must have advanced
        assertThat(second.updatedAt()).isAfterOrEqualTo(first.updatedAt());

        // Verify body was updated in DB
        Notification n = notificationRepository.findById(first.id()).orElseThrow();
        assertThat(n.getBody()).isEqualTo("Updated body");
        assertThat(n.getTitle()).isEqualTo("Still outbid");
    }

    @Test
    void insertsWhenOnlyMatchIsRead() {
        // Insert and then mark the row read
        UpsertResult first = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.OUTBID,
                        "You were outbid", "Body", null, "outbid:auction:3"));

        // Mark it read — the row no longer satisfies `WHERE read = false`
        txTemplate.executeWithoutResult(status ->
                notificationRepository.markRead(first.id(), userId));

        // Second upsert with same coalesce key must INSERT fresh (read row doesn't conflict)
        UpsertResult second = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.OUTBID,
                        "Outbid again", "Fresh body", null, "outbid:auction:3"));

        assertThat(second).isNotNull();
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(notificationRepository.countByUserIdAndReadFalse(userId)).isEqualTo(1L);
    }

    @Test
    void nullCoalesceKeyNeverConflicts() {
        UpsertResult first = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.SYSTEM_ANNOUNCEMENT,
                        "Announcement 1", "Body 1", null, null));

        UpsertResult second = txTemplate.execute(status ->
                dao.upsert(userId, NotificationCategory.SYSTEM_ANNOUNCEMENT,
                        "Announcement 2", "Body 2", null, null));

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // NULL != NULL → both must INSERT
        assertThat(first.wasUpdate()).isFalse();
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(notificationRepository.countByUserIdAndReadFalse(userId)).isEqualTo(2L);
    }

    @Test
    void concurrentUpsertOnSameKeyEndsAsOneRow() throws Exception {
        int threads = 8;
        String coalesceKey = "outbid:auction:concurrent";
        var executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger insertCount = new AtomicInteger();
        AtomicInteger updateCount = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                UpsertResult r = txTemplate.execute(status ->
                        dao.upsert(userId, NotificationCategory.OUTBID,
                                "Outbid #" + idx, "Body " + idx,
                                Map.of("idx", idx), coalesceKey));
                if (r.wasUpdate()) {
                    updateCount.incrementAndGet();
                } else {
                    insertCount.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        for (Future<Void> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one INSERT, (threads-1) UPDATEs, one row in DB
        assertThat(insertCount.get()).isEqualTo(1);
        assertThat(updateCount.get()).isEqualTo(threads - 1);
        assertThat(notificationRepository.countByUserIdAndReadFalse(userId)).isEqualTo(1L);
    }
}
