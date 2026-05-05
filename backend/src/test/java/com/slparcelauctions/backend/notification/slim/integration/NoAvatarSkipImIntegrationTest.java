package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.notification.NotificationService;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that users without a linked SL avatar never receive SL IM rows,
 * regardless of notification category or preferences.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class NoAvatarSkipImIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private final List<Long> userIds = new java.util.ArrayList<>();

    @BeforeEach
    void clean() {
        slImRepo.deleteAll();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : userIds) {
                    if (id != null) {
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + id);
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userIds.clear();
    }

    @Test
    void noAvatar_publishingMultipleCategories_zeroSlImRows() {
        User u = saveUserAllOnButNoAvatar();

        publish(u.getId(), NotificationCategory.OUTBID, "out", "body",
            Map.of("auctionId", 42), "outbid:" + u.getId() + ":42");
        publish(u.getId(), NotificationCategory.AUCTION_WON, "won", "body",
            Map.of("auctionId", 42), null);
        publish(u.getId(), NotificationCategory.ESCROW_FUNDED, "funded", "body",
            Map.of("auctionId", 42, "escrowId", 100), null);

        // Three categories published; in-app rows exist but zero SL IM rows.
        var rows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).toList();
        assertThat(rows).isEmpty();
    }

    private void publish(long userId, NotificationCategory category, String title,
                         String body, Map<String, Object> data, String coalesceKey) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            notificationService.publish(new NotificationEvent(
                userId, category, title, body, data, coalesceKey)));
    }

    private User saveUserAllOnButNoAvatar() {
        User u = User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build();
        // slAvatarUuid intentionally null
        u.setNotifySlImMuted(false);
        Map<String, Object> prefs = new HashMap<>();
        for (var g : List.of("bidding", "auction_result", "escrow",
                              "listing_status", "reviews", "system")) {
            prefs.put(g, true);
        }
        u.setNotifySlIm(prefs);
        User saved = userRepo.save(u);
        userIds.add(saved.getId());
        return saved;
    }
}
