package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for {@link NotificationRepository} queries.
 * Seeds rows directly through {@link NotificationDao} (which tests the
 * real ON CONFLICT path) and via the JPA save path for cases that need
 * fine-grained control over {@code read}.
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
class NotificationRepositoryTest {

    @Autowired NotificationDao dao;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepository;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate txTemplate;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void createUsers() {
        userId = userRepository.save(User.builder()
                .email("notif-repo-a-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build()).getId();
        otherUserId = userRepository.save(User.builder()
                .email("notif-repo-b-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build()).getId();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                for (Long id : new Long[]{userId, otherUserId}) {
                    if (id != null) {
                        stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                        stmt.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userId = null;
        otherUserId = null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Notification save(User user, NotificationCategory cat, boolean read) {
        return txTemplate.execute(status -> {
            Notification n = Notification.builder()
                    .user(user)
                    .category(cat)
                    .title("T")
                    .body("B")
                    .read(read)
                    .build();
            return repo.save(n);
        });
    }

    private User userRef(Long id) {
        return userRepository.findById(id).orElseThrow();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void findForUserByGroup_filtersByCategoriesInGroup() {
        User u = userRef(userId);
        save(u, NotificationCategory.OUTBID, false);
        save(u, NotificationCategory.AUCTION_WON, false);
        save(u, NotificationCategory.REVIEW_RECEIVED, false);

        Page<Notification> page = txTemplate.execute(status ->
                repo.findForUserByGroup(userId, NotificationGroup.BIDDING.categories(),
                        false, PageRequest.of(0, 10)));

        assertThat(page).isNotNull();
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCategory()).isEqualTo(NotificationCategory.OUTBID);
    }

    @Test
    void findForUserUnfiltered_returnsAllForUser_andRespectsUnreadOnly() {
        User u = userRef(userId);
        save(u, NotificationCategory.OUTBID, false);
        save(u, NotificationCategory.AUCTION_WON, true);  // read

        // unreadOnly=false → all 2
        Page<Notification> all = txTemplate.execute(status ->
                repo.findForUserUnfiltered(userId, false, PageRequest.of(0, 10)));
        assertThat(all).isNotNull();
        assertThat(all.getContent()).hasSize(2);

        // unreadOnly=true → only 1
        Page<Notification> unread = txTemplate.execute(status ->
                repo.findForUserUnfiltered(userId, true, PageRequest.of(0, 10)));
        assertThat(unread).isNotNull();
        assertThat(unread.getContent()).hasSize(1);
        assertThat(unread.getContent().get(0).getCategory()).isEqualTo(NotificationCategory.OUTBID);
    }

    @Test
    void findForUser_sortsByUpdatedAtDescThenIdDesc() throws InterruptedException {
        User u = userRef(userId);
        Notification old = save(u, NotificationCategory.OUTBID, false);

        // Upsert the old row so its updatedAt advances past the new row
        Thread.sleep(10);
        Notification fresh = save(u, NotificationCategory.AUCTION_WON, false);
        Thread.sleep(10);

        // Upsert old to bump its updatedAt to be most recent
        txTemplate.executeWithoutResult(status -> {
            old.setTitle("Updated");
            repo.save(old);
        });

        Page<Notification> page = txTemplate.execute(status ->
                repo.findForUserUnfiltered(userId, false, PageRequest.of(0, 10)));

        assertThat(page).isNotNull();
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(old.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(fresh.getId());
    }

    @Test
    void countUnreadByCategoryForUser_aggregatesCorrectly() {
        User u = userRef(userId);
        save(u, NotificationCategory.OUTBID, false);
        save(u, NotificationCategory.OUTBID, false);
        save(u, NotificationCategory.AUCTION_WON, false);
        save(u, NotificationCategory.AUCTION_WON, true); // read — should not count

        var counts = txTemplate.execute(status ->
                repo.countUnreadByCategoryForUser(userId));

        assertThat(counts).isNotNull();
        Map<NotificationCategory, Long> map = new java.util.HashMap<>();
        for (Object[] row : counts) {
            map.put((NotificationCategory) row[0], ((Number) row[1]).longValue());
        }

        assertThat(map.get(NotificationCategory.OUTBID)).isEqualTo(2L);
        assertThat(map.get(NotificationCategory.AUCTION_WON)).isEqualTo(1L);
    }

    @Test
    void markRead_isIdempotent() {
        User u = userRef(userId);
        Notification n = save(u, NotificationCategory.OUTBID, false);

        // First call → 1 row affected
        int first = txTemplate.execute(status -> repo.markRead(n.getId(), userId));
        assertThat(first).isEqualTo(1);

        // Second call on already-read row → 0 rows affected
        int second = txTemplate.execute(status -> repo.markRead(n.getId(), userId));
        assertThat(second).isEqualTo(0);

        // Row is now read
        assertThat(repo.countByUserIdAndReadFalse(userId)).isEqualTo(0L);
    }

    @Test
    void markAllReadByGroup_onlyAffectsThatGroup() {
        User u = userRef(userId);
        save(u, NotificationCategory.OUTBID, false);
        save(u, NotificationCategory.PROXY_EXHAUSTED, false);
        save(u, NotificationCategory.AUCTION_WON, false); // different group

        int affected = txTemplate.execute(status ->
                repo.markAllReadByGroup(userId, NotificationGroup.BIDDING.categories()));

        assertThat(affected).isEqualTo(2);

        // The AUCTION_RESULT notification must still be unread
        assertThat(repo.countByUserIdAndReadFalse(userId)).isEqualTo(1L);
        Page<Notification> stillUnread = txTemplate.execute(status ->
                repo.findForUserUnfiltered(userId, true, PageRequest.of(0, 10)));
        assertThat(stillUnread).isNotNull();
        assertThat(stillUnread.getContent().get(0).getCategory())
                .isEqualTo(NotificationCategory.AUCTION_WON);
    }

    @Test
    void existsByIdAndUserId_returnsFalseForOtherUser() {
        User u = userRef(userId);
        Notification n = save(u, NotificationCategory.OUTBID, false);

        assertThat(repo.existsByIdAndUserId(n.getId(), userId)).isTrue();
        assertThat(repo.existsByIdAndUserId(n.getId(), otherUserId)).isFalse();
    }
}
